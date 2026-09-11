package com.financial.news.config;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计时间自动填充拦截器（替代 MyBatis-Plus MetaObjectHandler）
 * <p>INSERT 时填充 createdAt、updatedAt，UPDATE 时填充 updatedAt；
 * 仅当实体存在该属性且当前值为 null 时填充，多参数语句（ParamMap）跳过</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class AuditTimeFillInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs()[1];
        SqlCommandType type = ms.getSqlCommandType();
        if (parameter != null && !(parameter instanceof Map)
                && (type == SqlCommandType.INSERT || type == SqlCommandType.UPDATE)) {
            LocalDateTime now = LocalDateTime.now();
            if (type == SqlCommandType.INSERT) {
                fillIfAbsent(parameter, "createdAt", now);
            }
            fillIfAbsent(parameter, "updatedAt", now);
        }
        return invocation.proceed();
    }

    private void fillIfAbsent(Object parameter, String property, LocalDateTime value) {
        try {
            PropertyDescriptor pd = new PropertyDescriptor(property, parameter.getClass());
            if (pd.getPropertyType() != LocalDateTime.class) {
                return;
            }
            if (pd.getReadMethod().invoke(parameter) == null) {
                pd.getWriteMethod().invoke(parameter, value);
            }
        } catch (IntrospectionException ignored) {
            // 实体没有该属性，无需填充
        } catch (Exception e) {
            throw new IllegalStateException("填充 " + property + " 失败: " + parameter.getClass().getSimpleName(), e);
        }
    }
}
