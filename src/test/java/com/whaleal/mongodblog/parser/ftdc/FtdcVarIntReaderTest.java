package com.whaleal.mongodblog.parser.ftdc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtdcVarIntReaderTest {
    private final FtdcVarIntReader reader = new FtdcVarIntReader();

    @Test
    void rejectsTruncatedVarInt() {
        assertThatThrownBy(() -> reader.read(new byte[]{(byte) 0x80}, new FtdcVarIntReader.Cursor(0)))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("截断");
    }

    @Test
    void rejectsOverflowingVarInt() {
        byte[] overflow = new byte[10];
        java.util.Arrays.fill(overflow, (byte) 0x80);
        overflow[9] = 0x02;

        assertThatThrownBy(() -> reader.read(overflow, new FtdcVarIntReader.Cursor(0)))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("溢出");
    }
}
