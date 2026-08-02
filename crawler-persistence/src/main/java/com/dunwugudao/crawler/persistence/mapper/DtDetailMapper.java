package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.DtDetail;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 龙虎榜席位明细 Mapper。
 * <p>主键 (ts_code, trade_date, seat_name)，幂等 upsert。</p>
 */
@Mapper
public interface DtDetailMapper extends BaseMapper<DtDetail> {

    /**
     * 幂等写入：以 (ts_code, trade_date, seat_name) 为自然键，冲突时更新全部业务列并刷新 data_source / src_detail。
     */
    @Insert("""
            INSERT INTO dt_detail
              (trade_date, ts_code, seat_name, seat_type, buy, sell,
               is_institution, is_famous, data_source, src_detail, create_date, update_date)
            VALUES
              (#{tradeDate}, #{tsCode}, #{seatName}, #{seatType}, #{buy}, #{sell},
               #{isInstitution}, #{isFamous}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate})
            ON CONFLICT (ts_code, trade_date, seat_name) DO UPDATE SET
              seat_type      = EXCLUDED.seat_type,
              buy            = EXCLUDED.buy,
              sell           = EXCLUDED.sell,
              is_institution = EXCLUDED.is_institution,
              is_famous      = EXCLUDED.is_famous,
              data_source    = EXCLUDED.data_source,
              src_detail     = EXCLUDED.src_detail,
              update_date    = EXCLUDED.update_date
            """)
    int insertOrUpdate(DtDetail row);

    /**
     * 读取某自然键已存在的最小 data_source，供优先级覆写裁决使用。
     * <p>按 (ts_code, trade_date, seat_name) 查，不加 data_source 条件——发现异源旧行。</p>
     * @return 已存在行最小优先级代码；无记录返回 null
     */
    @Select("""
            SELECT MIN(data_source) FROM dt_detail
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND seat_name = #{seatName}
            """)
    Integer selectDataSource(@Param("tsCode") String tsCode,
                             @Param("tradeDate") LocalDate tradeDate,
                             @Param("seatName") String seatName);
}
