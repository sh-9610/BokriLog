/*
 * BokriLog 백엔드의 Gradle 빌드 루트.
 *
 * `backend/`는 저장소 루트와 분리된 독립 빌드다. 프런트엔드(`frontend/`)는 npm이 소유하고
 * 두 빌드는 서로를 참조하지 않는다. 저장소 루트에 settings 파일을 두지 않는 이유도 같다.
 *
 * 하위 프로젝트를 만들지 않는다. Story 1.3이 Spring Modulith와 첫 모듈(`identity`)을 들였지만
 * Modulith의 모듈은 Gradle 서브프로젝트가 아니라 애플리케이션 클래스 패키지의 직속 하위
 * 패키지다. 경계는 빌드 구조가 아니라 `ModuleBoundaryTest`가 강제하므로 여기에 `include`가
 * 늘어날 일은 모듈이 생길 때마다 있는 것이 아니다.
 */
rootProject.name = "bokrilog-backend"
