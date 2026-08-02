package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.DtDetail;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 龙虎榜席位明细 Mapper。
 * <p>主键 (ts_code, trade_date, seat_name)，openGauss 兼容（无 ON CONFLICT）。</p>
 */
@Mapper
public interface DtDetailMapper extends BaseMapper<DtDetail> {

    @Select("""
            SELECT MIN(data_source) FROM dt_detail
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND seat_name = #{seatName}
            """)
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate,
                             @Param("seatName") String seatName);

    @Update("""
            UPDATE dt_detail SET
              seat_type = #{seatType}, buy = #{buy}, sell = #{sell},
              is_institution = #{isInstitution}, is_famous = #{isFamous},
              data_source = #{dataSource}, src_detail = #{srcDetail}, update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND seat_name = #{seatName}
            """)
    int updateRow(DtDetail row);

    @Insert("""
            INSERT INTO dt_detail
              (trade_date, ts_code, seat_name, seat_type, buy, sell,
               is_institution, is_famous, data_source, src_detail, create_date, update_date)
            SELECT
              #{tradeDate}, #{tsCode}, #{seatName}, #{seatType}, #{buy}, #{sell},
               #{isInstitution}, #{isFamous}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM dt_detail
              WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND seat_name = #{seatName}
            )
            """)
    int insertIfAbsent(DtDetail row);
}
