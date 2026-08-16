package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.BoardBasic;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * board_basic Mapper。
 * <p>去 BaseMapper 后补回等价原生 SQL 方法（selectList / updateById / insert / selectOneByBoardCode）。</p>
 */
@Mapper
public interface BoardBasicMapper {

    @Select("SELECT * FROM board_basic")
    List<BoardBasic> selectList(@Param("unused") Object unused);

    /** 去重 board_code 数(判断板块数是否变化)。 */
    @Select("SELECT count(DISTINCT board_code) FROM board_basic")
    Integer countDistinctBoardCodes();

    /** 查某 (board_type, board_code, data_source) 是否已存在（存在即跳过写入）。 */
    @Select("SELECT data_source FROM board_basic FINAL " +
            "WHERE board_type = #{boardType} AND board_code = #{boardCode} AND data_source = #{dataSource} " +
            "LIMIT 1")
    Integer selectDataSource(@Param("boardType") int boardType,
                             @Param("boardCode") String boardCode,
                             @Param("dataSource") int dataSource);

    @Update("""
            UPDATE board_basic SET
              board_name = #{boardName}, code = #{code}, features = #{features}, status = #{status},
              data_source = #{dataSource}, update_date = #{updateDate}
            WHERE board_type = #{boardType} AND board_code = #{boardCode} AND data_source = #{dataSource}
            """)
    int updateById(BoardBasic row);

    /** 按 board_code 查单条（供 SeedGenerator 用）。 */
    @Select("SELECT board_type, board_code, board_name, status, data_source FROM board_basic " +
            "WHERE board_code = #{boardCode} LIMIT 1")
    BoardBasic selectOneByBoardCode(@Param("boardCode") String boardCode);

    @Insert("INSERT INTO board_basic " +
            "(board_type, code, board_code, board_name, features, status, data_source, trade_date, create_date, update_date) " +
            "VALUES (#{boardType}, #{code}, #{boardCode}, #{boardName}, #{features}, #{status}, #{dataSource}, #{tradeDate}, #{createDate}, #{updateDate})")
    int insert(BoardBasic row);

    /** 按 board_type + trade_date 统计板块数(用于智能验证:比较昨天库中数 vs 市场总数)。 */
    @Select("SELECT count(DISTINCT board_code) FROM board_basic " +
            "WHERE board_type = #{boardType} AND data_source = #{dataSource} AND trade_date = #{tradeDate}")
    Integer countByBoardTypeAndTradeDate(@Param("boardType") int boardType,
                                         @Param("dataSource") int dataSource,
                                         @Param("tradeDate") String tradeDate);

    /** 按 board_type 查所有 board_code 集合(用于差集比较)。 */
    @Select("SELECT DISTINCT board_code FROM board_basic " +
            "WHERE board_type = #{boardType} AND data_source = #{dataSource}")
    List<String> selectBoardCodesByType(@Param("boardType") int boardType,
                                        @Param("dataSource") int dataSource);

    /** 查某类板块最新的 trade_date(用于智能验证对比,不局限于昨天)。 */
    @Select("SELECT MAX(trade_date) FROM board_basic " +
            "WHERE board_type = #{boardType} AND data_source = #{dataSource}")
    String selectLatestTradeDate(@Param("boardType") int boardType,
                                 @Param("dataSource") int dataSource);
}
