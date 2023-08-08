package com.dp.service;

import com.dp.dto.Result;
import com.dp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *


 */
public interface IShopTypeService extends IService<ShopType> {

    Result getTypeList();
}
