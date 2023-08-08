package com.dp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.dp.dto.Result;
import com.dp.entity.ShopType;
import com.dp.mapper.ShopTypeMapper;
import com.dp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.dp.utils.RedisConstants.TYPE_GEO_KEY;
import static com.dp.utils.RedisConstants.TYPE_GEO_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *


 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getTypeList() {
        //1. 查询redis
        String typeJson = stringRedisTemplate.opsForValue().get(TYPE_GEO_KEY);
        //存在直接返回
        if (StrUtil.isNotBlank(typeJson)) {
            List<ShopType> typeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(typeList);
        }
        //不存在,去数据库查询
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //2. 存入redis中
        stringRedisTemplate.opsForValue().set(TYPE_GEO_KEY, JSONUtil.toJsonStr(typeList), TYPE_GEO_TTL, TimeUnit.MINUTES);
        //3. 返回
        return Result.ok(typeList);
    }
}
