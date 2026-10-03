package co.yixiang.yshop.framework.apilog.core.interceptor;

import co.yixiang.yshop.framework.common.util.spring.SpringUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;


/**
 * API 访问日志 Interceptor
 *
 * 目的：在非 prod 环境时，打印 request 和 response 两条日志到日志文件（控制台）中。
 *
 * @author yshop
 */
@Slf4j
public class ApiAccessLogInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE_HANDLER_METHOD = "HANDLER_METHOD";

    private static final String ATTRIBUTE_STOP_WATCH = "ApiAccessLogInterceptor.StopWatch";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 记录 HandlerMethod，提供给 ApiAccessLogFilter 使用
        HandlerMethod handlerMethod = handler instanceof HandlerMethod ? (HandlerMethod) handler : null;
        if (handlerMethod != null) {
            request.setAttribute(ATTRIBUTE_HANDLER_METHOD, handlerMethod);
        }

        // 打印 request 日志
        if (!SpringUtils.isProd()) {
            // Request bodies/queries can contain codes, tokens or personal data.
            // Keep method, path and timing; never print raw request values.
            log.info("[preHandle][开始请求 method({}) URL({})]", request.getMethod(), request.getRequestURI());
            // 计时
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            request.setAttribute(ATTRIBUTE_STOP_WATCH, stopWatch);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 打印 response 日志
        if (!SpringUtils.isProd()) {
            StopWatch stopWatch = (StopWatch) request.getAttribute(ATTRIBUTE_STOP_WATCH);
            stopWatch.stop();
            log.info("[afterCompletion][完成请求 URL({}) 耗时({} ms)]",
                    request.getRequestURI(), stopWatch.getTotalTimeMillis());
        }
    }

}
