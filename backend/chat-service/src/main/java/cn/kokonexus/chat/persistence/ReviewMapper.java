package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.ChatReportReview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 审核审计只由审核事务插入，不提供业务删除接口。 */
public interface ReviewMapper extends BaseMapper<ChatReportReview> {}
