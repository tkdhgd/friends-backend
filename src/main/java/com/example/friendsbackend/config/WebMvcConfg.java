package com.example.friendsbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@Configuration
public class WebMvcConfg implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        //设置允许跨域的路径
        registry.addMapping("/**")
                //设置允许跨域请求的域名
                //当**Credentials为true时，**Origin不能为星号，需为具体的ip地址【如果接口不带cookie,ip无需设成具体ip】
//                .allowedOrigins("http://localhost:8080","http://localhost:3000")
                .allowedOrigins("http://localhost:8080", "https://www.arcfindteam.cn","https://arcfindteam.cn","https://www.arcfindteam.cn","http://www.arcfindteam.cn","http://arcfindteam.cn","http://localhost:3000","http://www.arcfindteam.cn","http://121.196.244.20:3000", "http://121.196.244.20:8080", "http://121.196.244.20:80", "http://121.196.244.20.5")
                //是否允许证书 不再默认开启
                .allowCredentials(true)
                //设置允许的方法
                .allowedMethods("*")
                //跨域允许时间
                .maxAge(3600);
    }
}