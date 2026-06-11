package com.hcx.streamhub.transcode.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hcx.streamhub.transcode.entity.TranscodeTask;

@Mapper
public interface TranscodeTaskMapper extends BaseMapper<TranscodeTask> {
}
