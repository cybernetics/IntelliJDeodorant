package ru.hse.godclass;

public class testSeparateBlocks {
    private int a;
    private int b;
    private int c;
    private int d;
    private int e;

    public void fun1() {
        synchronized (this) {
            a += 1;
            b += 1;
            c += 1;
        }
    }

    public void fun2() {
        synchronized (this) {
            d += 1;
            e += 1;
        }
    }
}