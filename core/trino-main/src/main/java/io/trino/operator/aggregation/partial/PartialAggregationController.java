/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.operator.aggregation.partial;

import io.airlift.units.DataSize;
import io.trino.operator.HashAggregationOperator;

import static java.util.Objects.requireNonNull;

/**
 * Controls whenever partial aggregation is enabled across all {@link HashAggregationOperator}s
 * for a particular plan node on a single node.
 * Partial aggregation is disabled after sampling sufficient amount of input
 * and the ratio between output(unique) and input rows is too high (> {@link #uniqueRowsRatioThreshold}).
 * TODO https://github.com/trinodb/trino/issues/11361 add support to adaptively re-enable partial aggregation.
 * <p>
 * The class is thread safe and objects of this class are used potentially by multiple threads/drivers simultaneously.
 * Different threads either:
 * - modify fields via synchronized {@link #onFlush}.
 * - read volatile {@link #partialAggregationDisabled} (volatile here gives visibility).
 */
public class PartialAggregationController
{
    /**
     * Process enough pages to fill up partial-aggregation buffer before
     * considering partial-aggregation to be turned off.
     */
    private static final double DISABLE_AGGREGATION_BUFFER_SIZE_TO_INPUT_BYTES_FACTOR = 1.5;

    private final DataSize maxPartialMemory;
    private final double uniqueRowsRatioThreshold;

    private volatile boolean partialAggregationDisabled;
    private long totalBytesProcessed;
    private long totalRowProcessed;
    private long totalUniqueRowsProduced;

    public PartialAggregationController(DataSize maxPartialMemory, double uniqueRowsRatioThreshold)
    {
        this.maxPartialMemory = requireNonNull(maxPartialMemory, "maxPartialMemory is null");
        this.uniqueRowsRatioThreshold = uniqueRowsRatioThreshold;
    }

    public boolean isPartialAggregationDisabled()
    {
        return partialAggregationDisabled;
    }

    public synchronized void onFlush(long bytesProcessed, long rowsProcessed, long uniqueRowsProduced)
    {
        totalBytesProcessed += bytesProcessed;
        totalRowProcessed += rowsProcessed;
        totalUniqueRowsProduced += uniqueRowsProduced;

        if (!partialAggregationDisabled && shouldDisablePartialAggregation()) {
            partialAggregationDisabled = true;
        }
    }

    private boolean shouldDisablePartialAggregation()
    {
        return totalBytesProcessed >= maxPartialMemory.toBytes() * DISABLE_AGGREGATION_BUFFER_SIZE_TO_INPUT_BYTES_FACTOR
                && ((double) totalUniqueRowsProduced / totalRowProcessed) > uniqueRowsRatioThreshold;
    }

    public PartialAggregationController duplicate()
    {
        return new PartialAggregationController(maxPartialMemory, uniqueRowsRatioThreshold);
    }
}
