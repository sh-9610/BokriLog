/*
 * BokriLog 백엔드의 Gradle 빌드 루트.
 *
 * `backend/`는 저장소 루트와 분리된 독립 빌드다. 프런트엔드(`frontend/`)는 npm이 소유하고
 * 두 빌드는 서로를 참조하지 않는다. 저장소 루트에 settings 파일을 두지 않는 이유도 같다.
 *
 * 하위 프로젝트를 만들지 않는다. Story 1.2는 모듈 경계를 도입하지 않으며
 * Spring Modulith와 `identity` 모듈은 Story 1.3의 범위다.
 */
rootProject.name = "bokrilog-backend"
