package com.hcx.streamhub.video.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hcx.streamhub.video.entity.Video;

@Mapper
public interface VideoMapper extends BaseMapper<Video> {
}
