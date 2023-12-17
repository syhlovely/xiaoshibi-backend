package com.xiaoshi.lookbi;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.compress.utils.IOUtils;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.apache.ibatis.io.Resources;
import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * @Author shiyinghan
 * @Date 2023/12/17 22:49
 * @PackageName:com.leetcode.test
 * @ClassName: IPLocal
 * @Description: TODO
 * @Version 1.0
 */
@SpringBootTest
public class IPLocal {

            public static void main(String[] args) throws IOException {
                //xbd文件不能直接读取到，需要通过文件流先读取到内存当中
                ClassPathResource resource = new ClassPathResource("ip2region/ip2region.xdb");
                InputStream inputStream = resource.getInputStream();
                byte[] bytes = IOUtils.toByteArray(inputStream);
                // 1、创建 searcher 对象
                String dbPath = "ip2region/ip2region.xdb";
                Searcher searcher = null;
                try {
//                    searcher = Searcher.newWithFileOnly(dbPath);
                    searcher = Searcher.newWithBuffer(bytes);

                } catch (IOException e) {
                    System.out.printf("failed to create searcher with `%s`: %s\n", dbPath, e);
                    return;
                }

                // 2、查询
                try {
                    String ip = "175.0.224.138";
                    long sTime = System.nanoTime();
                    String region = searcher.search(ip);
                    long cost = TimeUnit.NANOSECONDS.toMicros((long) (System.nanoTime() - sTime));
                    System.out.printf("{region: %s, ioCount: %d, took: %d μs}\n", region, searcher.getIOCount(), cost);
                } catch (Exception e) {
//                System.out.printf("failed to search(%s): %s\n", ip, e);
                }

                // 3、关闭资源
                searcher.close();

                // 备注：并发使用，每个线程需要创建一个独立的 searcher 对象单独使用。
            }
        }