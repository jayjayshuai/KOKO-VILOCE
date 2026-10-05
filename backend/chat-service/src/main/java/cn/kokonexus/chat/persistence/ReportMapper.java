package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.ChatReport;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 举报事实 CRUD；普通用户查询必须包含 reporterId。 */
public interface ReportMapper extends BaseMapper<ChatReport> {}
