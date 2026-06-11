package com.hcx.streamhub.comment.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hcx.streamhub.comment.entity.VideoComment;

@Mapper
public interface VideoCommentMapper extends BaseMapper<VideoComment> {
}
