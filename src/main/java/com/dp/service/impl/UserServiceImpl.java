package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.LoginFormDTO;
import com.dp.dto.Result;
import com.dp.dto.UserDTO;
import com.dp.entity.User;
import com.dp.mapper.UserMapper;
import com.dp.service.IUserService;
import com.dp.utils.RegexUtils;
import com.dp.utils.SystemConstants;
import com.dp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.dp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        // 如果不符合, 返回错误信息
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4. 发送验证码
        log.info("发送短信验证码成功, 验证码: " + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号
        // 如果不符合, 返回错误信息
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2. 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //不一致则报错
            return Result.fail("验证码错误");
        }
        //一致根据手机号查找用户
        User user = query().eq("phone", phone).one();
        //3. 判断用户是否存在
        if (user == null) {
            //不存在,创建新用户
            user = createUserWithPhone(phone);
        }
        //移除验证码
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        //4. 保存用户登录信息到redis当中
        //随机生成token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将User对象作为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置有效时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        //返回token
        return Result.ok(token);
    }

    @Override
    public Result queryUserById(Long id) {
        // 1.查询详情
        User user = getById(id);
        if (user == null) {
            return Result.fail("该用户不存在");
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 2.返回
        return Result.ok(userDTO);
    }

    private User createUserWithPhone(String phone) {
        //1. 创建用户
        User newUser = new User();
        newUser.setPhone(phone);
        newUser.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2. 保存用户
        save(newUser);
        return newUser;
    }

    @Override
    public Result sign() {
        // 1.获取当前登录的用户
        UserDTO user = UserHolder.getUser();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + user.getNickName() + keySuffix;
        // 4.获取今天是本月的第几天
        int day = now.getDayOfMonth();
        // 5.写入redis
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录的用户
        UserDTO user = UserHolder.getUser();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + user.getNickName() + keySuffix;
        // 4.获取今天是本月的第几天
        int day = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录,返回的是一个十进制的数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(day))
                .valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        // 6.循环遍历
        Long number = result.get(0);
        if (number == null || number == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            // 7.让这个数字与1做与运算,得到数字的最后一个bit位 // 判断这个bit位是否为0
            if ((number & 1) == 0) {
                // 如果为0,说明未签到,结束
                break;
            } else {
                // 如果不为0, 说明已签到,计数器+1
                count++;
            }
            // 把数字右移一位,抛弃最后一个bit位,继续下一个bit位
            number >>>= 1;
        }
        return Result.ok(count);
    }
}
