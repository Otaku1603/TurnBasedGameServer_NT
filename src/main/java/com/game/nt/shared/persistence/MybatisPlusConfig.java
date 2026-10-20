package com.game.nt.shared.persistence;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>只注册乐观锁拦截器（配合实体上的 {@code @Version} 字段）。
 * 分页插件需要额外的 jsqlparser 依赖，A0 暂不引入，避免无谓的体积与风险。
 */
@Configuration
@MapperScan(basePackages = "com.game.nt", annotationClass = Mapper.class)
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：所有写路径都应基于 version 字段做并发保护
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
