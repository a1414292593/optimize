package com.dp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

@SpringBootTest
class DianPingApplicationTests {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Test
    public void test() {

    }

}
