package com.dp.service;

import com.dp.dto.Result;
import com.dp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *


 */
public interface IShopService extends IService<Shop> {

    Result updateShop(Shop shop);

    Result queryWithLogical(Long id);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
