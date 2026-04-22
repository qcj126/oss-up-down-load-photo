package diary.ossupdownloadphoto.config.threadpoolconfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ossUploadExecutor")
    public Executor ossUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);                // 核心线程数
        executor.setMaxPoolSize(10);                // 最大线程数
        executor.setQueueCapacity(100);             // 队列容量
        executor.setThreadNamePrefix("oss-upload-"); // 线程名前缀
        executor.initialize();
        return executor;
    }

    @Bean(name = "ossDownloadExecutor")
    public Executor ossDownloadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);                // 下载核心线程数
        executor.setMaxPoolSize(20);                // 下载最大线程数
        executor.setQueueCapacity(200);             // 下载队列容量
        executor.setThreadNamePrefix("oss-download-"); // 下载线程名前缀
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
