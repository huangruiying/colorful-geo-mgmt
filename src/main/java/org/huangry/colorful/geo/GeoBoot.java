package org.huangry.colorful.geo;


import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 对接whatapp
 *
 * @author huangry
 * Created in 2024/11/13 14:24
 */

@SpringBootApplication()
@ComponentScan("org.huangry.colorful.geo")
@Slf4j
public class GeoBoot {

	public static void main(String[] args) {
		SpringApplication.run(GeoBoot.class, args);
		log.info("localhost:8010/admin/index.html");
	}

}
