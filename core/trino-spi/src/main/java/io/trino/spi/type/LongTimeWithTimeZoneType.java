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
package io.trino.spi.type;

import io.airlift.slice.XxHash64;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.BlockBuilderStatus;
import io.trino.spi.block.Fixed12BlockBuilder;
import io.trino.spi.block.PageBuilderStatus;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.BlockIndex;
import io.trino.spi.function.BlockPosition;
import io.trino.spi.function.FlatFixed;
import io.trino.spi.function.FlatFixedOffset;
import io.trino.spi.function.FlatVariableWidth;
import io.trino.spi.function.ScalarOperator;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import static io.airlift.slice.SizeOf.SIZE_OF_LONG;
import static io.trino.spi.function.OperatorType.COMPARISON_UNORDERED_LAST;
import static io.trino.spi.function.OperatorType.EQUAL;
import static io.trino.spi.function.OperatorType.HASH_CODE;
import static io.trino.spi.function.OperatorType.LESS_THAN;
import static io.trino.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static io.trino.spi.function.OperatorType.READ_VALUE;
import static io.trino.spi.function.OperatorType.XX_HASH_64;
import static io.trino.spi.type.TimeWithTimeZoneTypes.normalizePicos;
import static io.trino.spi.type.TypeOperatorDeclaration.extractOperatorDeclaration;
import static java.lang.String.format;
import static java.lang.invoke.MethodHandles.lookup;

final class LongTimeWithTimeZoneType
        extends TimeWithTimeZoneType
{
    private static final TypeOperatorDeclaration TYPE_OPERATOR_DECLARATION = extractOperatorDeclaration(LongTimeWithTimeZoneType.class, lookup(), LongTimeWithTimeZone.class);
    private static final VarHandle INT_HANDLE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    public LongTimeWithTimeZoneType(int precision)
    {
        super(precision, LongTimeWithTimeZone.class);

        if (precision < MAX_SHORT_PRECISION + 1 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException(format("Precision must be in the range [%s, %s]", MAX_SHORT_PRECISION + 1, MAX_PRECISION));
        }
    }

    @Override
    public TypeOperatorDeclaration getTypeOperatorDeclaration(TypeOperators typeOperators)
    {
        return TYPE_OPERATOR_DECLARATION;
    }

    @Override
    public int getFixedSize()
    {
        return Long.BYTES + Integer.BYTES;
    }

    @Override
    public BlockBuilder createBlockBuilder(BlockBuilderStatus blockBuilderStatus, int expectedEntries, int expectedBytesPerEntry)
    {
        int maxBlockSizeInBytes;
        if (blockBuilderStatus == null) {
            maxBlockSizeInBytes = PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES;
        }
        else {
            maxBlockSizeInBytes = blockBuilderStatus.getMaxPageSizeInBytes();
        }
        return new Fixed12BlockBuilder(
                blockBuilderStatus,
                Math.min(expectedEntries, maxBlockSizeInBytes / getFixedSize()));
    }

    @Override
    public BlockBuilder createBlockBuilder(BlockBuilderStatus blockBuilderStatus, int expectedEntries)
    {
        return createBlockBuilder(blockBuilderStatus, expectedEntries, getFixedSize());
    }

    @Override
    public BlockBuilder createFixedSizeBlockBuilder(int positionCount)
    {
        return new Fixed12BlockBuilder(null, positionCount);
    }

    @Override
    public void appendTo(Block block, int position, BlockBuilder blockBuilder)
    {
        if (block.isNull(position)) {
            blockBuilder.appendNull();
        }
        else {
            write(blockBuilder, getPicos(block, position), getOffsetMinutes(block, position));
        }
    }

    @Override
    public Object getObject(Block block, int position)
    {
        return new LongTimeWithTimeZone(getPicos(block, position), getOffsetMinutes(block, position));
    }

    @Override
    public void writeObject(BlockBuilder blockBuilder, Object value)
    {
        LongTimeWithTimeZone timestamp = (LongTimeWithTimeZone) value;
        write(blockBuilder, timestamp.getPicoseconds(), timestamp.getOffsetMinutes());
    }

    private static void write(BlockBuilder blockBuilder, long picoseconds, int offsetMinutes)
    {
        ((Fixed12BlockBuilder) blockBuilder).writeFixed12(picoseconds, offsetMinutes);
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        return SqlTimeWithTimeZone.newInstance(getPrecision(), getPicos(block, position), getOffsetMinutes(block, position));
    }

    @Override
    public int getFlatFixedSize()
    {
        return Long.BYTES + Integer.BYTES;
    }

    private static long getPicos(Block block, int position)
    {
        return block.getLong(position, 0);
    }

    private static int getOffsetMinutes(Block block, int position)
    {
        return block.getInt(position, SIZE_OF_LONG);
    }

    @ScalarOperator(READ_VALUE)
    private static LongTimeWithTimeZone readFlat(
            @FlatFixed byte[] fixedSizeSlice,
            @FlatFixedOffset int fixedSizeOffset,
            @FlatVariableWidth byte[] unusedVariableSizeSlice)
    {
        return new LongTimeWithTimeZone(
                (long) LONG_HANDLE.get(fixedSizeSlice, fixedSizeOffset),
                (int) INT_HANDLE.get(fixedSizeSlice, fixedSizeOffset + Long.BYTES));
    }

    @ScalarOperator(READ_VALUE)
    private static void readFlatToBlock(
            @FlatFixed byte[] fixedSizeSlice,
            @FlatFixedOffset int fixedSizeOffset,
            @FlatVariableWidth byte[] unusedVariableSizeSlice,
            BlockBuilder blockBuilder)
    {
        write(blockBuilder,
                (long) LONG_HANDLE.get(fixedSizeSlice, fixedSizeOffset),
                (int) INT_HANDLE.get(fixedSizeSlice, fixedSizeOffset + Long.BYTES));
    }

    @ScalarOperator(READ_VALUE)
    private static void writeFlat(
            LongTimeWithTimeZone value,
            byte[] fixedSizeSlice,
            int fixedSizeOffset,
            byte[] unusedVariableSizeSlice,
            int unusedVariableSizeOffset)
    {
        LONG_HANDLE.set(fixedSizeSlice, fixedSizeOffset, value.getPicoseconds());
        INT_HANDLE.set(fixedSizeSlice, fixedSizeOffset + SIZE_OF_LONG, value.getOffsetMinutes());
    }

    @ScalarOperator(READ_VALUE)
    private static void writeBlockFlat(
            @BlockPosition Block block,
            @BlockIndex int position,
            byte[] fixedSizeSlice,
            int fixedSizeOffset,
            byte[] unusedVariableSizeSlice,
            int unusedVariableSizeOffset)
    {
        LONG_HANDLE.set(fixedSizeSlice, fixedSizeOffset, getPicos(block, position));
        INT_HANDLE.set(fixedSizeSlice, fixedSizeOffset + SIZE_OF_LONG, getOffsetMinutes(block, position));
    }

    @ScalarOperator(EQUAL)
    private static boolean equalOperator(LongTimeWithTimeZone left, LongTimeWithTimeZone right)
    {
        return equal(
                left.getPicoseconds(),
                left.getOffsetMinutes(),
                right.getPicoseconds(),
                right.getOffsetMinutes());
    }

    @ScalarOperator(EQUAL)
    private static boolean equalOperator(@BlockPosition Block leftBlock, @BlockIndex int leftPosition, @BlockPosition Block rightBlock, @BlockIndex int rightPosition)
    {
        return equal(
                getPicos(leftBlock, leftPosition),
                getOffsetMinutes(leftBlock, leftPosition),
                getPicos(rightBlock, rightPosition),
                getOffsetMinutes(rightBlock, rightPosition));
    }

    private static boolean equal(long leftPicos, int leftOffsetMinutes, long rightPicos, int rightOffsetMinutes)
    {
        return normalizePicos(leftPicos, leftOffsetMinutes) == normalizePicos(rightPicos, rightOffsetMinutes);
    }

    @ScalarOperator(HASH_CODE)
    private static long hashCodeOperator(LongTimeWithTimeZone value)
    {
        return hashCodeOperator(value.getPicoseconds(), value.getOffsetMinutes());
    }

    @ScalarOperator(HASH_CODE)
    private static long hashCodeOperator(@BlockPosition Block block, @BlockIndex int position)
    {
        return hashCodeOperator(getPicos(block, position), getOffsetMinutes(block, position));
    }

    private static long hashCodeOperator(long picos, int offsetMinutes)
    {
        return AbstractLongType.hash(normalizePicos(picos, offsetMinutes));
    }

    @ScalarOperator(XX_HASH_64)
    private static long xxHash64Operator(LongTimeWithTimeZone value)
    {
        return xxHash64(value.getPicoseconds(), value.getOffsetMinutes());
    }

    @ScalarOperator(XX_HASH_64)
    private static long xxHash64Operator(@BlockPosition Block block, @BlockIndex int position)
    {
        return xxHash64(getPicos(block, position), getOffsetMinutes(block, position));
    }

    private static long xxHash64(long picos, int offsetMinutes)
    {
        return XxHash64.hash(normalizePicos(picos, offsetMinutes));
    }

    @ScalarOperator(COMPARISON_UNORDERED_LAST)
    private static long comparisonOperator(LongTimeWithTimeZone left, LongTimeWithTimeZone right)
    {
        return comparison(
                left.getPicoseconds(),
                left.getOffsetMinutes(),
                right.getPicoseconds(),
                right.getOffsetMinutes());
    }

    @ScalarOperator(COMPARISON_UNORDERED_LAST)
    private static long comparisonOperator(@BlockPosition Block leftBlock, @BlockIndex int leftPosition, @BlockPosition Block rightBlock, @BlockIndex int rightPosition)
    {
        return comparison(
                getPicos(leftBlock, leftPosition),
                getOffsetMinutes(leftBlock, leftPosition),
                getPicos(rightBlock, rightPosition),
                getOffsetMinutes(rightBlock, rightPosition));
    }

    private static long comparison(long leftPicos, int leftOffsetMinutes, long rightPicos, int rightOffsetMinutes)
    {
        return Long.compare(normalizePicos(leftPicos, leftOffsetMinutes), normalizePicos(rightPicos, rightOffsetMinutes));
    }

    @ScalarOperator(LESS_THAN)
    private static boolean lessThanOperator(LongTimeWithTimeZone left, LongTimeWithTimeZone right)
    {
        return lessThan(
                left.getPicoseconds(),
                left.getOffsetMinutes(),
                right.getPicoseconds(),
                right.getOffsetMinutes());
    }

    @ScalarOperator(LESS_THAN)
    private static boolean lessThanOperator(@BlockPosition Block leftBlock, @BlockIndex int leftPosition, @BlockPosition Block rightBlock, @BlockIndex int rightPosition)
    {
        return lessThan(
                getPicos(leftBlock, leftPosition),
                getOffsetMinutes(leftBlock, leftPosition),
                getPicos(rightBlock, rightPosition),
                getOffsetMinutes(rightBlock, rightPosition));
    }

    private static boolean lessThan(long leftPicos, int leftOffsetMinutes, long rightPicos, int rightOffsetMinutes)
    {
        return normalizePicos(leftPicos, leftOffsetMinutes) < normalizePicos(rightPicos, rightOffsetMinutes);
    }

    @ScalarOperator(LESS_THAN_OR_EQUAL)
    private static boolean lessThanOrEqualOperator(LongTimeWithTimeZone left, LongTimeWithTimeZone right)
    {
        return lessThanOrEqual(
                left.getPicoseconds(),
                left.getOffsetMinutes(),
                right.getPicoseconds(),
                right.getOffsetMinutes());
    }

    @ScalarOperator(LESS_THAN_OR_EQUAL)
    private static boolean lessThanOrEqualOperator(@BlockPosition Block leftBlock, @BlockIndex int leftPosition, @BlockPosition Block rightBlock, @BlockIndex int rightPosition)
    {
        return lessThanOrEqual(
                getPicos(leftBlock, leftPosition),
                getOffsetMinutes(leftBlock, leftPosition),
                getPicos(rightBlock, rightPosition),
                getOffsetMinutes(rightBlock, rightPosition));
    }

    private static boolean lessThanOrEqual(long leftPicos, int leftOffsetMinutes, long rightPicos, int rightOffsetMinutes)
    {
        return normalizePicos(leftPicos, leftOffsetMinutes) <= normalizePicos(rightPicos, rightOffsetMinutes);
    }
}
