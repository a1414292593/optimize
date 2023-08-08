package com.dp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 超时时间
     * @return 是否获取到锁
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
