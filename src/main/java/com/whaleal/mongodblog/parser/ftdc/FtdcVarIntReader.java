package com.whaleal.mongodblog.parser.ftdc;

public final class FtdcVarIntReader {
    public long read(byte[] input, Cursor cursor) {
        long result = 0;
        for (int index = 0; index < 10; index++) {
            if (cursor.position >= input.length) {
                throw new FtdcFormatException("uvarint 截断");
            }
            int next = input[cursor.position++] & 0xff;
            if (index == 9 && (next & 0xfe) != 0) {
                throw new FtdcFormatException("uvarint 溢出");
            }
            result |= (long) (next & 0x7f) << (index * 7);
            if ((next & 0x80) == 0) {
                return result;
            }
        }
        throw new FtdcFormatException("uvarint 溢出");
    }

    public static final class Cursor {
        private int position;

        public Cursor(int position) {
            this.position = position;
        }

        public int position() {
            return position;
        }
    }
}
