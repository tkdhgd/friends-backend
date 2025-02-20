package com.example.friendsbackend.service.impl;
import java.util.Date;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.friendsbackend.common.Code;
import com.example.friendsbackend.exception.BusinessException;
import com.example.friendsbackend.mapper.UserMapper;
import com.example.friendsbackend.modal.domain.User;
import com.example.friendsbackend.modal.request.UpdateTagRequest;
import com.example.friendsbackend.modal.request.UserQueryRequest;
import com.example.friendsbackend.modal.request.UserRegister;
import com.example.friendsbackend.service.UserService;
import com.example.friendsbackend.utils.AlgorithmUtils;
import com.example.friendsbackend.utils.BloomFilterUtil;
import com.example.friendsbackend.utils.RedisUtil;
import com.example.friendsbackend.utils.StringJsonListToLongSet;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.example.friendsbackend.constant.Constant.*;
import static com.example.friendsbackend.utils.StringJsonListToLongSet.stringJsonListToLongSet;

/**
* @author BDS
* @description 针对表【user(用户)】的数据库操作Service实现
* @createDate 2024-05-12 15:46:39
*/
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{
    @Resource
    private UserMapper userMapper;
    public static final String SALT = "YUPI";
    @Resource
    private RedisTemplate<String,Object> redisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisUtil redisUtil;
    @Resource
    private BloomFilterUtil bloomFilterUtil;
    private  RBloomFilter<Object> bloomFilter;
    // 预测插入数量
    static long expectedInsertions = 55000L;
    // 误判率
    static double falseProbability = 0.01;

    @PostConstruct
    public void init(){
        // 初始化 Bloom Filter
        // 首先删除旧的布隆过滤器，如果布隆过滤器参数没有变化的话可以不删
        bloomFilterUtil.deleteBloomFilter("userAccountWhiteList");
//        RLock lock = redissonClient.getLock("gamefriend-d:user:recentUser:lock");
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("userAccount");
        List<User> userList = this.list(queryWrapper);
        bloomFilter = bloomFilterUtil.create("userAccountWhiteList", expectedInsertions, falseProbability);
        for (User user : userList){
            bloomFilter.add(user.getUserAccount());
        }

        // 缓存最近登录用户表
        RLock lock = redissonClient.getLock("gamefriend-d:user:recentUser:lock");
        try {
            if (lock.tryLock(0, 1, TimeUnit.MINUTES)) { // 使用更合适的时间单位
                // 查询最近登录的1000个用户
                queryWrapper = new QueryWrapper<>();
                queryWrapper.select("id", "lastTime");
                queryWrapper.orderByDesc("lastTime");
                queryWrapper.last("limit 0,1000");
                userList = userMapper.selectList(queryWrapper);
                for (User user : userList){
                    redisUtil.zsetSet(RECENTUSER, user.getId(), user.getLastTime().getTime());
                }

                // 查询 SVIP 和 VIP 用户
                queryWrapper = new QueryWrapper<>();
                queryWrapper.select("id", "lastTime");
                queryWrapper.in("vipState", '1', '2'); // 使用 in() 方法代替多个 eq() 方法
                userList = userMapper.selectList(queryWrapper);
                for (User user : userList){
                    redisUtil.zsetSet(RECENTUSER, user.getId(), user.getLastTime().getTime());
                }
            }
        } catch (InterruptedException e) {
            log.error("doCacheRecentUser error", e);
        } finally {
            if (lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
    }



    @Override
    public long userRegister(UserRegister userRegister) {
        String username = userRegister.getUsername();
        String userAccount = userRegister.getUserAccount();
        String userPassword = userRegister.getUserPassword();
        String checkPassword = userRegister.getCheckPassword();
        if (StringUtils.isAnyBlank(username,userAccount,userPassword,checkPassword))
            return 0;
        if (userAccount.length() < 4) {
            throw new BusinessException(Code.PARAMS_ERROR,"账户长度小于4位");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(Code.PARAMS_ERROR,"密码长度小于8位");
        }
        //账户不能包含特殊符号
        String validPattern = "\\pP|\\pS|\\s+";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);
        if (matcher.find()) {
            throw new BusinessException(Code.PARAMS_ERROR,"账户包含特殊字符");
        }
        //两次密码校验
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(Code.PARAMS_ERROR,"两次密码不一致");
        }
        //账号不能重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        long count = userMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException(Code.PARAMS_ERROR,"当前账户名已注册");
        }
        //2.密码加密
        String savePassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        //3.插入数据
        User user = new User();
        user.setUserName(username);
        user.setUserUrl("https://tse2-mm.cn.bing.net/th/id/OIP-C.Ekyqq_T0BDXvef1bfI2qDQHaHa?w=213&h=213&c=7&r=0&o=5&pid=1.7");
        user.setGender(0);
        user.setUserAccount(userAccount);
        user.setUserPassword(savePassword);
        user.setUserStatus(0);
        user.setCreateTime(new Date());
        user.setUpdateTime(new Date());
        user.setLastTime(new Date());
        user.setIsDelete(0);
        user.setUserRole(0);

        boolean result = this.save(user);
        if (!result) {
            throw new BusinessException(Code.SAVE_ERROR,"请重试");
        }
        bloomFilter.add(user.getUserAccount());
        return user.getId();
    }

    /**
     * 用户登录
     *
     * @param userAccount  用户账号
     * @param userPassword 用户密码
     * @param request 客户端请求
     * @return 用户信息
     */
    @Override
    public User userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(Code.PARAMS_ERROR,"输入参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(Code.PARAMS_ERROR,"账户长度小于4位");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(Code.PARAMS_ERROR,"密码长度小于8位");
        }
        //账户不能包含特殊符号
        String validPattern = "\\pP|\\pS|\\s+";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);
        if (matcher.find()) {
            throw new BusinessException(Code.PARAMS_ERROR,"账户包含特殊字符");
        }

        //2.密码加密
        String savePassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());

        //检查用户账号信息
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", savePassword);
        User user = userMapper.selectOne(queryWrapper);
        if (user == null) {
            log.info("user login failed, userAccount cannot math userPassword");
            throw new BusinessException(Code.PARAM_NULL_ERROR,"数据库中不包含该账户");
        }
//        更新用户最后登录时间
        Date date = new Date();
        user.setLastTime(date);
        userMapper.updateById(user);
//        RedisUtil redisUtil = new RedisUtil();
//        将用户id存储到最近登录缓存中
        redisUtil.zsetSet(RECENTUSER,user.getId(),user.getLastTime().getTime());
        //3.用户信息脱敏
        User safeUser = getSafeUser(user);

        //4.记录用户登录状态
        request.getSession().setAttribute(USER_LOGIN_STATE, safeUser);
        //返回脱敏后的用户信息
        return safeUser;

    }
    @Override
    public User getSafeUser(User originUser){
        User safeUser = new User();
        safeUser.setId(originUser.getId());
        safeUser.setUserAccount(originUser.getUserAccount());
        safeUser.setUserName(originUser.getUserName());
        safeUser.setUserUrl(originUser.getUserUrl());
        safeUser.setProfile(originUser.getProfile());
        safeUser.setGender(originUser.getGender());
        safeUser.setVipState(originUser.getVipState());
        safeUser.setLastTime(originUser.getLastTime());
        safeUser.setTags(originUser.getTags());
        safeUser.setPhone(originUser.getPhone());
        safeUser.setEmail(originUser.getEmail());
        safeUser.setUserStatus(originUser.getUserStatus());
        safeUser.setCreateTime(originUser.getCreateTime());
        safeUser.setUserRole(originUser.getUserRole());
        safeUser.setFriendsIds(originUser.getFriendsIds());
        return safeUser;
    }

    @Override
    public int loginOut(HttpServletRequest request) {
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return 1;
    }


    @Override
    public List<User> searchUserByTag(long num, List<String> tagsNameList){

//        System.out.println("数据开始查询时间戳："+System.currentTimeMillis());
//        // sql查询包含输入标签的用户
//        if (CollectionUtils.isEmpty(tagsNameList)){
//            throw new BusinessException(Code.PARAMS_ERROR);
//        }
//////        //1.sql查询   like "%java%"
//        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
//        queryWrapper.select("id","tags");
//        for (String tagName:tagsNameList){
//            queryWrapper = queryWrapper.like("tags",tagName);
//        }
//        queryWrapper.orderByDesc("createTime");
//        queryWrapper.last("limit 1,100");
//        List<User> userList = userMapper.selectList(queryWrapper);

        // 从缓存中读取最近登录用户的id,2629800000表示一个月对应的毫秒数
        Set<Object> userIdSet = redisUtil.zsetAllQuery(RECENTUSER, System.currentTimeMillis() - RECENTTIME, System.currentTimeMillis());

        // 1. 查询到所有用户 id 和标签
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        if (userIdSet != null && !userIdSet.isEmpty()){
            queryWrapper.in("id",userIdSet);
        }
        queryWrapper.select("id","tags");
//        queryWrapper.isNotNull("tags");
//        queryWrapper.orderByDesc("lastTime");
//        queryWrapper.last("limit 0,1000");
        List<User> userList = this.list(queryWrapper);
//
//        System.out.println("查询1000条用户后时间戳："+System.currentTimeMillis());

        // 2. 获取用户标签，使用 gson 将 json 格式数据转换为字符串列表
        Gson gson = new Gson();
        // 3. 根据标签查询相似度高的用户
        // 存储用户信息与其对应的相似度得分
        List<Pair<User,Integer>> list = new ArrayList<>();
        for (int i = 0; i < userList.size(); i++) {
            User user = userList.get(i);
            String userTags = user.getTags();
            // 这里考虑删除，上面queryWrapper已经判断isNotNull
            if (StringUtils.isBlank(userTags)){
                continue;
            }
            List<String> userTagList = gson.fromJson(userTags, new TypeToken<List<String>>() {
            }.getType());
            int minDistance = AlgorithmUtils.minDistance(tagsNameList, userTagList);
            list.add(new Pair<>(user,minDistance));
        }
        // 对 list 中的数据进行排序
        List<Pair<User, Integer>> topUserPairList = list.stream()
                .sorted(Comparator.comparingInt(Pair::getValue))
                .limit(num)
                .collect(Collectors.toList());
        // 读取当前 list 中用户对应的 id
        List<Long> topUserIdList = topUserPairList.stream().map(pair -> pair.getKey().getId()).collect(Collectors.toList());
        // 读取 id 列表对应用户信息并且将用户信息脱敏
        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
        userQueryWrapper.in("id",topUserIdList);
        Map<Long, List<User>> userIdListMap = this.list(userQueryWrapper)
                .stream()
                .map(this::getSafeUser)
                .collect(Collectors.groupingBy(User::getId));
        //
        List<User> resultList = new ArrayList<>();
        for (Long userId : topUserIdList) {
            resultList.add(userIdListMap.get(userId).get(0));
        }
        System.out.println("相似度排序后时间戳："+System.currentTimeMillis());
        return resultList;


        //用户信息脱敏
//        return  userList.stream().map(this::getSafeUser).collect(Collectors.toList());
//
//        //内存查询
//        //1.先将所有数据放到内存中
//        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
//        List<User> userList = userMapper.selectList(queryWrapper);
//        Gson gson = new Gson();
//        //2.判断
////        Set<String> tagNameSet = new HashSet<>(tagsNameList);
////        List<User> filterUser = new ArrayList<>();
////        for (User user:userList){
////            String tags = user.getTags();
////            if (StringUtils.isBlank(tags)){
////                continue;
////            }
////            Set<String> tempTagName = gson.fromJson(tags,new TypeToken<Set<String>>(){}.getType());
////            boolean containAllTags = true;
////            for (String tagName : tagNameSet){
////                if (!tempTagName.contains(tagName)){
////                    containAllTags = false;
////                    break;
////                }
////            }
////            if (containAllTags){
////                filterUser.add(user);
////            }
////        }
////        return filterUser;
//
//        return  userList.stream().filter(user -> {
//            String tags = user.getTags();
//
////            if (StringUtils.isBlank(tags)) {
////                return false;
////            }
//            Set<String> tempTagName = gson.fromJson(tags, new TypeToken<Set<String>>() {
//            }.getType());
//            tempTagName = Optional.ofNullable(tempTagName).orElse(new HashSet<>());
//            System.out.println(tempTagName);
//            for (String tagName : tagsNameList) {
//                if (!tempTagName.contains(tagName)) {
//                    return false;
//                }
//            }
//            return true;
//        }).map(this::getSafeUser).collect(Collectors.toList());

    }

    @Override
    public List<User> recommendUser(HttpServletRequest request, long pageSize, long pageNum) {
        Long id = -1L;
        if (request != null){
            User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
            if (loginUser != null){
                id = loginUser.getId();
            }
        }
        //根据用户id查询缓存值
        ValueOperations<String, Object> opsForValue = redisTemplate.opsForValue();
        String rediasKey = String.format("gamefriend-d:user:recommend:%s",id);
//        String rediasKey = String.format("gamefriend-d:user:%s",loginUser.getId());
        List<User> userList = (List<User>) opsForValue.get(rediasKey);
//        //如果有缓存值，直接返回
        if (userList != null){
            return userList;
        }

        //如果没有缓存值，从最近登录用户表中读取
        System.out.println("从最近登录用户表中读取：");
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        // 从缓存中读取最近登录用户的id,2629800000表示一个月对应的毫秒数
        Set<Object> userIdSet = redisUtil.zsetAllQuery(RECENTUSER, System.currentTimeMillis() - RECENTTIME, System.currentTimeMillis());
        if (userIdSet != null && !userIdSet.isEmpty()){
            queryWrapper.in("id",userIdSet);

        }
        queryWrapper.ne("id",id);
        queryWrapper.orderByDesc("vipState");
//        queryWrapper.last("limit 0,1000");

//        queryWrapper.select("id","tags");

        Page<User> userPage = this.page(new Page<>(pageNum, pageSize), queryWrapper);
        // 返回的用户信息要脱敏
        userList = userPage.getRecords().stream().map(
                this::getSafeUser).collect(Collectors.toList());
        //写入缓存
        try {
            opsForValue.set(rediasKey,userList,10, TimeUnit.MICROSECONDS);
        } catch (Exception e) {
            log.error("redias set key error",e);
        }
        return userList;
    }

    @Override
    @Cacheable(cacheNames = "userAccount", key = "#userAccount", unless = "#result==null") //缓存存在，则使用缓存；不存在，则执行方法，并将结果塞入缓存
    public List<User> searchUserByUserAccount(String userAccount) {
        if (!bloomFilter.contains(userAccount)){
            System.out.println("所要查询的数据既不在缓存中，也不在数据库中，为非法key");
            /*
              设置unless = "#result==null"并在非法访问的时候返回null的目的是不将该次查询返回的null使用
              RedissonConfig-->RedisCacheManager-->RedisCacheConfiguration-->entryTtl设置的过期时间存入缓存。

              因为那段时间太长了，在那段时间内可能该非法key又添加到bloomFilter，比如之前不存在userAccount为1234567的用户，
              在那段时间可能刚好userAccount为1234567的用户完成注册，使该key成为合法key。

              所以我们需要在缓存中添加一个可容忍的短期过期的null或者是其它自定义的值,使得短时间内直接读取缓存中的该值。

              因为Spring Cache本身无法缓存null，因此选择设置为一个其中所有值均为null的JSON，
             */
            String illegalJson = "[\"com.company.springboot.entity.User\",{\"id\":null,\"userName\":\"null\",]";
            // RedissonClient的getBucket(key)方法获取一个RBucket对象实例，通过这个实例可以设置value或设置value和有效期
            redissonClient.getBucket("userAccount::" + userAccount).set(illegalJson,new Random().nextInt(200) + 300, TimeUnit.SECONDS);
//            redissonClient.getBucket("userAccount::" + userAccount, new StringCodec()).set(illegalJson,new Random().nextInt(200) + 300, TimeUnit.SECONDS);
            return null;
        }
        System.out.println("从数据库中查询用户");
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount",userAccount);
        List<User> userList = this.list(queryWrapper);
        return userList.stream().map(this::getSafeUser).collect(Collectors.toList());
    }

    @Override
    public int updateUser(User user, HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if (user == null){
            throw new BusinessException(Code.PARAM_NULL_ERROR);
        }
        //获取当前用户id，int默认值为0，数据库中不存在id为0的用户
        Long id = user.getId();
        if (id == null || id == 0){
            throw new BusinessException(Code.PARAMS_ERROR,"未提供id");
        }
        //判断当前用户权限
        if (!isAdmin(loginUser) && loginUser.getId() != user.getId()){
            throw new BusinessException(Code.NO_AUTH);
        }
        //修改信息
        return userMapper.updateById(user);
    }

    @Override
    public int updateTagById(UpdateTagRequest tagRequest, User loginUser) {
        long userId = tagRequest.getId();
        if (userId <= 0){
            throw new BusinessException(Code.PARAMS_ERROR,"该用户不存在");
        }
        Set<String> newTags = tagRequest.getTagList();
        if (newTags.size() > 12){
            throw new BusinessException(Code.PARAMS_ERROR,"最多设置12个标签");
        }
        if (!isAdmin(loginUser) && userId != loginUser.getId()){
            throw new BusinessException(Code.NO_AUTH,"没有权限");
        }
        User user = userMapper.selectById(userId);
        Gson gson = new Gson();
        Set<String> oldTags = gson.fromJson(user.getTags(), new TypeToken<Set<String>>() {
        }.getType());
        Set<String> oldTagsCapitalize = toCapitalize(oldTags);
        Set<String> newTagsCapitalize = toCapitalize(newTags);

        // 添加 newTagsCapitalize 中 oldTagsCapitalize 中不存在的元素
        oldTagsCapitalize.addAll(newTagsCapitalize.stream().filter(tag -> !oldTagsCapitalize.contains(tag)).collect(Collectors.toSet()));
        // 移除 oldTagsCapitalize 中 newTagsCapitalize 中不存在的元素
        oldTagsCapitalize.removeAll(oldTagsCapitalize.stream().filter(tag -> !newTagsCapitalize.contains(tag)).collect(Collectors.toSet()));
        String tagsJson = gson.toJson(oldTagsCapitalize);
        user.setTags(tagsJson);
        return userMapper.updateById(user);

    }

    @Override
    public User getLoginUser(HttpServletRequest request)  {
        if (request == null){
            throw new BusinessException(Code.NO_LOGIN);
        }
        User userLogin = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userLogin == null){
            throw new BusinessException(Code.NO_LOGIN);
        }
        return userLogin;
    }

    /**
     * 检查用户权限
     *
     * @param request session
     * @return 是否为管理员
     */
    @Override
    public boolean isAdmin(HttpServletRequest request){
        User currentUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (currentUser == null || currentUser.getUserRole() != ADMIN_ROLE) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isAdmin(User user){
        if (user == null || user.getUserRole() != ADMIN_ROLE) {
            return false;
        }
        return true;
    }

    @Override
    public List<User> getFriendsById(User loginUser) {
        User user = userMapper.selectById(loginUser.getId());
        // 获取当前用户好友的id
        String friendsIds = user.getFriendsIds();
        Set<Long> friendsIdList = stringJsonListToLongSet(friendsIds);
        // 根据好友id查询好友信息
        return friendsIdList.stream()
                .map(id -> this.getSafeUser(userMapper.selectById(id)))
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteFriend(User loginUser, Long id) {
        String friendsIds = loginUser.getFriendsIds();
        Set<Long> friendsIdSet = stringJsonListToLongSet(friendsIds);
        if (!friendsIdSet.contains(id)){
            throw new BusinessException(Code.PARAMS_ERROR,"不是您的好友用户");
        }
        User friendUser = this.getById(id);
        Set<Long> fid = stringJsonListToLongSet(friendUser.getFriendsIds());
        if (!friendsIdSet.remove(id)){
            throw new BusinessException(Code.SAVE_ERROR,"请重试");
        }
        fid.remove(loginUser.getId());
        Gson gson = new Gson();
        String friends = gson.toJson(friendsIdSet);
        String fids = gson.toJson(fid);
        loginUser.setFriendsIds(friends);
        friendUser.setFriendsIds(fids);
        return this.updateById(loginUser) && this.updateById(friendUser);
    }

    @Override
    public List<User> searchFriend(UserQueryRequest userQueryRequest, User loginUser) {
        // 获取名称
        String userAccount = userQueryRequest.getSearchText();
        // 获取所有的用户信息
//        // 优先在以添加的好友中查询
//        List<User> friends = this.getFriendsById(loginUser);
//        List<User> result = new ArrayList<>();
//        friends.forEach(user -> {
//            if (user.getUserName().contains(searchText)){
//                result.add(user);
//            }
//        });
        // 在最近登录的用户的中查询
        return this.searchUserByUserAccount(userAccount);
    }

    @Override
    public List<User> matchUsers(long num, User loginUser) {
        /*String tags = loginUser.getTags();
        Gson gson = new Gson();
        List<String> loginUserTagList = gson.fromJson(tags, new TypeToken<List<String>>() {
        }.getType());
        return this.searchUserByTag(num,loginUserTagList);*/

        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("id", "tags");
        queryWrapper.isNotNull("tags");
        List<User> userList = this.list(queryWrapper);
        String tags = loginUser.getTags();
        Gson gson = new Gson();
        List<String> tagList = gson.fromJson(tags, new TypeToken<List<String>>() {
        }.getType());
        // 用户列表的下标 => 相似度
        List<Pair<User, Long>> list = new ArrayList<>();
        // 依次计算所有用户和当前用户的相似度
        for (int i = 0; i < userList.size(); i++) {
            User user = userList.get(i);
            String userTags = user.getTags();
            // 无标签或者为当前用户自己
            if (StringUtils.isBlank(userTags) || user.getId() == loginUser.getId()) {
                continue;
            }
            List<String> userTagList = gson.fromJson(userTags, new TypeToken<List<String>>() {
            }.getType());
            // 计算分数
            long distance = AlgorithmUtils.minDistance(tagList, userTagList);
            list.add(new Pair<>(user, distance));
        }
        // 按编辑距离由小到大排序
        List<Pair<User, Long>> topUserPairList = list.stream()
                .sorted((a, b) -> (int) (a.getValue() - b.getValue()))
                .limit(num)
                .collect(Collectors.toList());
        // 原本顺序的 userId 列表
        List<Long> userIdList = topUserPairList.stream().map(pair -> pair.getKey().getId()).collect(Collectors.toList());
        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
        userQueryWrapper.in("id", userIdList);
        // 1, 3, 2
        // User1、User2、User3
        // 1 => User1, 2 => User2, 3 => User3
        Map<Long, List<User>> userIdUserListMap = this.list(userQueryWrapper)
                .stream()
                .map(user -> getSafeUser(user))
                .collect(Collectors.groupingBy(User::getId));
        List<User> finalUserList = new ArrayList<>();
        for (Long userId : userIdList) {
            finalUserList.add(userIdUserListMap.get(userId).get(0));
        }
        return finalUserList;
    }

    /**
     * String类型集合首字母大写
     *
     * @param oldSet 原集合
     * @return 首字母大写的集合
     */
    private Set<String> toCapitalize(Set<String> oldSet) {
        return oldSet.stream().map(StringUtils::capitalize).collect(Collectors.toSet());
    }
}




