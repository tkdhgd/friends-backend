package com.example.friendsbackend.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserTeamServiceTest {
    @Resource
    private UserTeamService userTeamService;

    @Test
    void test(){
        String userId = "1";
        System.out.println("String:"+userId);
        System.out.println(Long.valueOf(userId));
        System.out.println();
        List<String> teamList = userTeamService.findTeamList(1L);
        System.out.println(teamList.size());
    }
}