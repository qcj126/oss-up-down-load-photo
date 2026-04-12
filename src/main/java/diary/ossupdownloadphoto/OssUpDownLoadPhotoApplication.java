package diary.ossupdownloadphoto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class OssUpDownLoadPhotoApplication {

    public static void main(String[] args) {
        SpringApplication.run(OssUpDownLoadPhotoApplication.class, args);
        log.info("应用已启动，端口为:{}", "9999");
    }

}
