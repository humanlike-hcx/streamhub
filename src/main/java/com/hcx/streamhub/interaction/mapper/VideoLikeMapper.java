package com.hcx.streamhub.interaction.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hcx.streamhub.interaction.entity.VideoLike;

@Mapper
public interface VideoLikeMapper extends BaseMapper<VideoLike> {
}
