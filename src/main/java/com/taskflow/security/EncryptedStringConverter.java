package com.taskflow.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * 컬럼 값을 AES-256-GCM으로 암호화해 저장한다.
 *
 * <p>대상은 Google OAuth access/refresh token이다. refresh token은 Google에 원문 그대로
 * 제시해야 access token을 재발급받으므로 단방향 해시를 쓸 수 없다. 따라서 목적은 값을
 * 숨기는 것이 아니라 <b>열쇠를 DB 밖으로 빼는 것</b>이다. 키는 환경변수로만 들어오므로
 * pg_dump 덤프·볼륨 복사·DB 계정 탈취처럼 DB만 새는 경로에서는 토큰을 복원할 수 없다.
 * 앱 서버 자체가 털리면 키도 함께 새므로 그 경우는 막지 못한다.
 *
 * <p>키를 바꾸면 기존 암호문은 영구히 복호화할 수 없다. 교체 시 사용자는 Google 재연결이 필요하다.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final TextEncryptor encryptor;

    public EncryptedStringConverter(
            @Value("${token.encryption.password}") String password,
            @Value("${token.encryption.salt}") String salt) {
        // delux = AES-256-GCM. salt는 hex 문자열이어야 한다.
        this.encryptor = Encryptors.delux(password, salt);
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : encryptor.decrypt(dbData);
    }
}
