package com.game.nt.cluster.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 集群 Pub/Sub 容器：订阅本节点推送/指令通道与全节点广播通道。
 */
@Configuration
public class ClusterRedisConfig {

    @Bean(destroyMethod = "destroy")
    public RedisMessageListenerContainer clusterListenerContainer(RedisConnectionFactory connectionFactory,
                                                                  ClusterService clusterService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        clusterService.subscribe(container);
        return container;
    }
}
