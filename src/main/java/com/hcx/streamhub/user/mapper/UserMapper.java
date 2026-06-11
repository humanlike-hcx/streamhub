package com.hcx.streamhub.user.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hcx.streamhub.user.entity.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
