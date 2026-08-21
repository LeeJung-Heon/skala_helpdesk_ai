package com.example.lab3;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 컨텍스트 로딩 확인.
 *
 * <p><b>{@code local} 프로필이 필요하다.</b> 기본 프로필은 pgvector 와 JDBC 대화 메모리를 쓰므로
 * Postgres 가 떠 있어야 한다. 테스트가 Docker 를 요구하면 CI 에서도 로컬에서도 쉽게 깨진다 —
 * 그래서 인메모리 구성으로 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class Lab3ApplicationTests {

	@Test
	void contextLoads() {
	}

}
