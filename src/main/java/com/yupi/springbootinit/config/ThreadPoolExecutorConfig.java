package com.yupi.springbootinit.config;

import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @Author shiyinghan
 * @Date 2023/8/7 17:43
 * @PackageName:com.yupi.springbootinit.config
 * @ClassName: ThreadPoolExcutorConfig
 * @Description: TODO
 * @Version 1.0
 */
@Configuration
public class ThreadPoolExecutorConfig {

    /**
     * 配置线程池
     * @return
     */
    @Bean
    public ThreadPoolExecutor getThreadPllExecutor(){
        return new ThreadPoolExecutor(2,
                4,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(20));
    }


}
