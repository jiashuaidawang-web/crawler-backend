package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.MainFundFlow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 主力资金流 Mapper。
 * <p>主键 (obj_type, ts_code, board_code, index_code, trade_date)，幂等 upsert。</p>
 */
@Mapper
public interface MainFundFlowMapper extends BaseMapper<MainFundFlow> {

    /**
     * 幂等写入：以 (obj_type, ts_code, board_code, index_code, trade_date) 为自然键，冲突时更新全部业务列并刷新 data_source / src_detail。
     */
    @Insert("""
            INSERT INTO main_fund_flow
              (trade_date, obj_type, ts_code, board_code, index_code,
               main_net, super_big, big_net, mid_net, small_net,
               data_source, src_detail, create_date, update_date)
            VALUES
              (#{tradeDate}, #{objType}, #{tsCode}, #{boardCode}, #{indexCode},
               #{mainNet}, #{superBig}, #{bigNet}, #{midNet}, #{smallNet},
               #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate})
            ON CONFLICT (obj_type, ts_code, board_code, index_code, trade_date) DO UPDATE SET
              main_net     = EXCLUDED.main_net,
              super_big    = EXCLUDED.super_big,
              big_net      = EXCLUDED.big_net,
              mid_net      = EXCLUDED.mid_net,
              small_net    = EXCLUDED.small_net,
              data_source  = EXCLUDED.data_source,
              src_detail   = EXCLUDED.src_detail,
              update_date  = EXCLUDED.update_date
            """)
    int insertOrUpdate(MainFundFlow row);

    /**
     * 读取某自然键已存在的最小 data_source，供优先级覆写裁决使用。
     * <p>按完整五列主键查，不加 data_source 条件——发现异源旧行，让高优先级源能覆盖。</p>
     * @return 已存在行最小优先级代码；无记录返回 null
     */
    @Select("""
            SELECT MIN(data_source) FROM main_fund_flow
            WHERE obj_type = #{objType} AND ts_code = #{tsCode} AND board_code = #{boardCode}
              AND index_code = #{indexCode} AND trade_date = #{tradeDate}
            """)
    Integer selectDataSource(@Param("objType") String objType,
                             @Param("tsCode") String tsCode,
                             @Param("boardCode") String boardCode,
                             @Param("indexCode") String indexCode,
                             @Param("tradeDate") LocalDate tradeDate);
}
