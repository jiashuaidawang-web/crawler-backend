package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.StockBoardRel;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 板块-个股关联关系 Mapper。
 * <p>主键 (board_code, ts_code, board_type, data_source) 由数据库唯一约束兜底防重。</p>
 */
@Mapper
public interface StockBoardRelMapper extends BaseMapper<StockBoardRel> {

    /**
     * 幂等写入：以 (board_code, ts_code, board_type, data_source) 为主键，冲突时更新业务列。
     */
    @Insert("""
            INSERT INTO stock_board_rel
              (ts_code, board_code, board_name, board_type, is_leader, is_midarm, weight,
               effective_date, data_source, src_detail, create_date, update_date)
            VALUES
              (#{tsCode}, #{boardCode}, #{boardName}, #{boardType}, #{isLeader}, #{isMidarm}, #{weight},
               #{effectiveDate}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate})
            ON CONFLICT (board_code, ts_code, board_type, data_source) DO UPDATE SET
              board_name    = EXCLUDED.board_name,
              is_leader     = EXCLUDED.is_leader,
              is_midarm     = EXCLUDED.is_midarm,
              weight        = EXCLUDED.weight,
              effective_date = EXCLUDED.effective_date,
              data_source   = EXCLUDED.data_source,
              src_detail    = EXCLUDED.src_detail,
              update_date   = EXCLUDED.update_date
            """)
    int insertOrUpdate(StockBoardRel row);

    /**
     * 读取某自然键已存在的最小 data_source，供优先级覆写裁决使用。
     * <p>按 (board_code, ts_code, board_type) 查，不加 data_source 条件——
     * 这样才能发现「异源旧行」，让高优先级源（代码小）能覆盖低优先级源。
     * 返回多行时取最小 source（最高优先级）。</p>
     * @return 已存在行最小优先级代码；无记录返回 null
     */
    @Select("""
            SELECT MIN(data_source) FROM stock_board_rel
            WHERE board_code = #{boardCode} AND ts_code = #{tsCode} AND board_type = #{boardType}
            """)
    Integer selectDataSource(@Param("boardCode") String boardCode,
                             @Param("tsCode") String tsCode,
                             @Param("boardType") Integer boardType);
}
