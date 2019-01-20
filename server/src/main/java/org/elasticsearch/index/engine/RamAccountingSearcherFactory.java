/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.engine;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.IndexSearcher.LeafSlice;
import org.apache.lucene.search.SearcherFactory;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.indices.breaker.CircuitBreakerService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Searcher factory extending {@link EngineSearcherFactory} that tracks the
 * amount of memory used by segments in the accounting circuit breaker.
 */
final class RamAccountingSearcherFactory extends SearcherFactory {

    private static final int MAX_DOCS_PER_SLICE = 250_000;
    private static final int MAX_SEGMENTS_PER_SLICE = 5;

    private final CircuitBreakerService breakerService;
    private final ExecutorService executorService;

    RamAccountingSearcherFactory(CircuitBreakerService breakerService) {
        this.breakerService = breakerService;
        this.executorService = null;
    }

    RamAccountingSearcherFactory(CircuitBreakerService breakerService,
            ExecutorService executorService) {
        this.breakerService = breakerService;
        this.executorService = executorService;
    }

    /** Holds one group of LeafReaderContext. */
    private static class Leaves {
        public final List<LeafReaderContext> leaves = new ArrayList<>();

        public Leaves(LeafReaderContext ctx) {
            leaves.add(ctx);
        }

        public LeafSlice toSlice() {
            return new LeafSlice(leaves.toArray(new LeafReaderContext[0]));
        }
    }

    /** Groups small leaves together so we can search them more efficiently with a single thread. */
    LeafSlice[] getSearchSlices(List<LeafReaderContext> leaves) {
        // Make a copy so we can sort:
        leaves = new ArrayList<>(leaves);

        // Sort by maxDoc, descending:
        Collections.sort(leaves,
                new Comparator<LeafReaderContext>() {
                    @Override
                    public int compare(LeafReaderContext a, LeafReaderContext b) {
                        return -Integer.compare(a.reader().maxDoc(),
                                                b.reader().maxDoc());
                    }
                });

        // Coalesce leaves into slices with >= 250K docs, or 5 leaves, whichever comes first, so we don't
        // dispatch threads on tiny-ish segments:
        final List<Leaves> groupedLeaves = new ArrayList<>();
        long docSum = 0;
        Leaves group = null;
        for (LeafReaderContext ctx : leaves) {
            if (ctx.reader().maxDoc() > MAX_DOCS_PER_SLICE) {
                assert group == null;
                groupedLeaves.add(new Leaves(ctx));
            } else {
                if (group == null) {
                    group = new Leaves(ctx);
                    groupedLeaves.add(group);
                } else {
                    group.leaves.add(ctx);
                }

                docSum += ctx.reader().maxDoc();
                if (group.leaves.size() >= MAX_SEGMENTS_PER_SLICE || docSum > MAX_DOCS_PER_SLICE) {
                    group = null;
                    docSum = 0;
                }
            }
        }

        LeafSlice[] slices = new LeafSlice[groupedLeaves.size()];
        int upto = 0;
        for (Leaves currentLeaf : groupedLeaves) {
            slices[upto++] = currentLeaf.toSlice();
        }

        return slices;
    }

    @Override
    public IndexSearcher newSearcher(IndexReader reader, IndexReader previousReader) throws IOException {
        processReaders(reader, previousReader);

        if (executorService != null) {
            return new IndexSearcher(reader, executorService) {
                @Override
                protected LeafSlice[] slices(List<LeafReaderContext> leaves) {
                    return getSearchSlices(leaves);
                }
            };
        }

        return new IndexSearcher(reader);
    }

    public void processReaders(IndexReader reader, IndexReader previousReader) {
        final CircuitBreaker breaker = breakerService.getBreaker(CircuitBreaker.ACCOUNTING);

        // Construct a list of the previous segment readers, we only want to track memory used
        // by new readers, so these will be exempted from the circuit breaking accounting.
        //
        // The Core CacheKey is used as the key for the set so that deletions still keep the correct
        // accounting, as using the Reader or Reader's CacheKey causes incorrect accounting.
        final Set<IndexReader.CacheKey> prevReaders;
        if (previousReader == null) {
            prevReaders = Collections.emptySet();
        } else {
            final List<LeafReaderContext> previousReaderLeaves = previousReader.leaves();
            prevReaders = new HashSet<>(previousReaderLeaves.size());
            for (LeafReaderContext lrc : previousReaderLeaves) {
                prevReaders.add(Lucene.segmentReader(lrc.reader()).getCoreCacheHelper().getKey());
            }
        }

        for (LeafReaderContext lrc : reader.leaves()) {
            final SegmentReader segmentReader = Lucene.segmentReader(lrc.reader());
            // don't add the segment's memory unless it is not referenced by the previous reader
            // (only new segments)
            if (prevReaders.contains(segmentReader.getCoreCacheHelper().getKey()) == false) {
                final long ramBytesUsed = segmentReader.ramBytesUsed();
                // add the segment memory to the breaker (non-breaking)
                breaker.addWithoutBreaking(ramBytesUsed);
                // and register a listener for when the segment is closed to decrement the
                // breaker accounting
                segmentReader.getCoreCacheHelper().addClosedListener(k -> breaker.addWithoutBreaking(-ramBytesUsed));
            }
        }
    }
}
