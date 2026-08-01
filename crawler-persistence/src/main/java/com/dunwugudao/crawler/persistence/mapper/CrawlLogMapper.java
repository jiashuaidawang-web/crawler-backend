package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.CrawlLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrawlLogMapper extends BaseMapper<CrawlLog> {
}
