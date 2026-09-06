# BokriLog 백엔드 런타임 이미지.
#
# 빌드 단계가 없다. 이 이미지는 `backend/gradlew build`가 이미 만든 jar 하나만 올린다.
# CI가 `backend-quality`에서 검증한 그 산출물을 `same-origin-integration`이 다시 빌드하지 않고
# 그대로 소비해야 하기 때문이다(CI-PLAN §8). 이미지 안에서 다시 빌드하면 통합 시험이 검증하는
# 바이트가 품질 job이 검증한 바이트와 같다는 보장이 사라진다.
#
# 베이스는 `ARCHITECTURE-SPINE.md` Stack 표의 런타임 이미지다. production 배포는 tag가 아니라
# digest로 고정하지만 그 고정은 Story 3.8의 Release Gate가 소유한다. 여기서는 개발·CI용 tag를 쓴다.
FROM eclipse-temurin:25-jre-alpine-3.24

# root로 실행하지 않는다. 이 이미지에는 패키지 설치도 쓰기도 없다.
RUN addgroup -S bokrilog && adduser -S -G bokrilog -H -s /sbin/nologin bokrilog

WORKDIR /app

# `.dockerignore`가 이 파일 하나만 컨텍스트에 남긴다. 소스·Gradle 캐시·로컬 설정은 들어가지 않는다.
COPY --chown=bokrilog:bokrilog build/libs/bokrilog-backend.jar /app/app.jar

USER bokrilog

# 컨테이너 안의 포트다. 바깥에 열리는 포트는 Nginx 하나뿐이며 compose가 이 포트를 게시하지 않는다.
EXPOSE 8080

# 셸을 거치지 않는다(exec form). PID 1이 JVM이어야 compose의 stop 신호가 graceful shutdown으로 간다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
