package diary.ossupdownloadphoto.config.resultconfig;

import lombok.AllArgsConstructor;

import java.util.Map;

@AllArgsConstructor
public class ResultDto {
    private Map<String, Object> respDto;

    public static ResultDto isSuccess(Map<String, Object> respDto) {
        return new ResultDto(respDto);
    }
}
