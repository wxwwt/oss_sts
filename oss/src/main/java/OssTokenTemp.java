import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.aliyuncs.sts.model.v20150401.AssumeRoleRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleResponse;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;

/**
 * ossToken
 *
 * @author scott
 */
@Slf4j
public class OssTokenTemp {

    private static final String ROLE_ARN = "arn";

    private static final String BUCKET = "bucket";

    private static final String FOLDER = "folder";

    private static final String SESSION_NAME = "test-session-name";

    private static final String END_POINT = "http://oss-cn-hangzhou.aliyuncs.com/";

    private static final String ACCESS_KEY_ID = "id";

    private static final String ACCESS_KEY_SECRET = "secret";

    public static void main(String[] args) throws Exception {
        String picUrl = "https://pics6.baidu.com/feed/2f738bd4b31c8701d8cc6baf515ba6290708ff28.jpeg?token=300950b83aef179374a6821342676ebf&s=A512C07C1EA1C9725EB657830200F08B";
        OssTokenTemp temp = new OssTokenTemp();
        Map<String, String> resultMap = temp.getToken();

        OSS ossClient = new OSSClientBuilder().build(END_POINT, resultMap.get("accessKeyId"), resultMap.get("accessKeySecret"), resultMap.get("securityToken"));
        temp.upload2Oss(ossClient, "测试token", picUrl);
    }

    public Map<String, String> getToken() {
        String policy = "{\n" +
                "    \"Statement\": [\n" +
                "      {\n" +
                "        \"Action\": [\n" +
                "          \"oss:PutObject\"\n" +
                "        ],\n" +
                "        \"Effect\": \"Allow\",\n" +
                "        \"Resource\": [\"acs:oss:*:*:wxwwt-oss/*\", \"acs:oss:*:*:wxwwt-oss\"]\n" +
                "      }\n" +
                "    ],\n" +
                "    \"Version\": \"1\"\n" +
                "  }";
        Map<String, String> resultMap = Maps.newHashMap();
        try {
            // 添加endpoint（直接使用STS endpoint，前两个参数留空，无需添加region ID）
            DefaultProfile.addEndpoint("", "cn-hangzhou", "Sts", END_POINT);

            // 构造default profile（参数留空，无需添加region ID）
            IClientProfile profile = DefaultProfile.getProfile("cn-hangzhou", ACCESS_KEY_ID, ACCESS_KEY_SECRET);
            // 用profile构造client
            DefaultAcsClient client = new DefaultAcsClient(profile);
            final AssumeRoleRequest request = new AssumeRoleRequest();
            request.setMethod(MethodType.POST);
            request.setRoleArn(ROLE_ARN);
            request.setRoleSessionName(SESSION_NAME);
            // 若policy为空，则用户将获得该角色下所有权限
            request.setPolicy(policy);
            // 设置凭证有效时间 单位秒  范围在15分钟到1小时
            request.setDurationSeconds(3600L);

            final AssumeRoleResponse response = client.getAcsResponse(request);
            resultMap.put("expiration", response.getCredentials().getExpiration());
            resultMap.put("accessKeyId", response.getCredentials().getAccessKeyId());
            resultMap.put("accessKeySecret", response.getCredentials().getAccessKeySecret());
            resultMap.put("securityToken", response.getCredentials().getSecurityToken());
        } catch (Exception e) {
            log.info("上传异常：", e);
        }
        return resultMap;
    }

    /**
     * 上传本地文件/网络url流对象/sftp流对象到oss
     *
     * @param key
     * @param filePath
     * @return
     * @throws IOException
     */
    public void upload2Oss(OSS ossClient, String key, String filePath) throws Exception {
        log.info("图片:" + key + ", 地址:" + filePath);
        try (InputStream is = readInputStream(filePath)) {
            if (is == null) {
                log.error("图片" + key + "资源不存在");
            } else {
                uploadByStream(key, is, filePath, ossClient);
            }
        }
    }

    public static InputStream readInputStream(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        try (InputStream inputStream = url.openStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len = 0;

            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            return new ByteArrayInputStream(outputStream.toByteArray());
        }
    }

    /**
     * 流式上传的基础方法
     *
     * @param key
     * @param inputStream
     * @param filePath    图片路径
     * @return
     */
    public void uploadByStream(String key, InputStream inputStream, String filePath, OSS ossClient) throws Exception {
        String md5Key = null;
        log.info("【根据文件路径名判断图片类型】 文件路径名 : " + filePath);
        String fileType = null;
        try {
            fileType = getContentSuffix(filePath);
            String ossKey = FOLDER + "/" + key + "." + fileType;
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(inputStream.available());
            metadata.setCacheControl("no-cache");
            metadata.setHeader("Pragma", "no-cache");
            metadata.setContentEncoding("utf-8");
            metadata.setContentType(fileType);
            metadata.setContentDisposition("filename/filesize=" + key + "/" + inputStream.available() + "Byte.");
            log.info(key + "上传oss开始");
            PutObjectResult putResult = ossClient.putObject(BUCKET, ossKey, inputStream, metadata);
            md5Key = putResult.getETag();
            if (StringUtils.isNotBlank(md5Key)) {
                log.info(key + "上传oss成功");
            } else {
                log.warn(key + "上传失败");
            }
        } catch (Exception e) {
            log.error(key + "上传失败", e);
        } finally {
            inputStream.close();
        }
    }

    public String getContentSuffix(String fileName) {
        // 文件的后缀名
        int extIndex = fileName.lastIndexOf(".");
        if (extIndex == -1) {
            return "jpg";
        }
        // 判断文件类型的长度是否超出合法长度，如果大于4则为不合法, 返回jpg
        int pictureFormatLength = fileName.substring(extIndex + 1).length();
        if(pictureFormatLength > 4){
            return "jpg";
        }
        return fileName.substring(extIndex + 1);
    }
}


