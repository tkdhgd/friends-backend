package com.example.friendsbackend.service.impl;
import java.util.Date;

import com.example.friendsbackend.mapper.UserMapper;
import com.example.friendsbackend.modal.domain.User;
import com.example.friendsbackend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StopWatch;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 本测试用于生成数据库数据，在打包时需要删除本测试或者跳过测试打包
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class InsertUsers {

    @Resource
    private UserService userService;

    //线程设置
    private ExecutorService executorService = new ThreadPoolExecutor(16,1000,10000, TimeUnit.MINUTES, new ArrayBlockingQueue<>(10000));
    @Test
    public void doInsertUser(){
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        final int INSERT_NUM = 5;
        int batchSize = 1;
        int j = 0;
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (int i = 0; i < INSERT_NUM / batchSize; i++){
            List<User> userList = new ArrayList<>();
            while (true){
                j++;
                User user = new User();
                user.setUserAccount("fake");
                user.setUserName("fake");
                user.setUserUrl("https://picx.zhimg.com/80/v2-a67f86b7702594cc75899f23615aef1d_720w.webp?source=1def8aca");
                user.setProfile("");
                user.setGender(0);
                user.setUserPassword("a1a6c667b32d27a7a8e09f189ba7bba9");
                user.setPhone("123456789@qq.com");
                user.setEmail("123456789@qq.com");

                user.setVipState("0");
                user.setTags("[\"军事\",\"视觉小说\"]");
                userList.add(user);
                if (j % batchSize == 0){
                    break;
                }
            }
            //异步执行
            CompletableFuture<Void> future = CompletableFuture.runAsync(()->{
                System.out.println("ThreadName" + Thread.currentThread().getName());
                userService.saveBatch(userList);
            },executorService);
            futureList.add(future);
        }
        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[]{})).join();
        stopWatch.stop();
        System.out.println(stopWatch.getLastTaskTimeMillis());
    }

}
