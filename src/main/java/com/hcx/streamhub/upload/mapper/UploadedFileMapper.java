package com.hcx.streamhub.upload.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hcx.streamhub.upload.entity.UploadedFile;

@Mapper
public interface UploadedFileMapper extends BaseMapper<UploadedFile> {
}
