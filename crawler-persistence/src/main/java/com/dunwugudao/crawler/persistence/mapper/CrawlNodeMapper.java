package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.CrawlNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrawlNodeMapper extends BaseMapper<CrawlNode> {
}
