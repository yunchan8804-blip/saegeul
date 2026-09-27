# scripts/

이 저장소의 보조 스크립트 모음이다. 각 스크립트는 저장소 루트에서 실행한다.

## 릴리스 게이트

CI(`.github/workflows/release-contract.yml`, `user-release.yml`)가 실행하는 검증 스크립트다. 인자는 `docs/wiki/09-Developer-and-Build-Guide.md` 4절에도 정리돼 있다.

- `verify-release-licenses.ps1` — 서명된 APK에 포함된 오픈소스 라이선스 고지가 빠지지 않았는지 확인한다.
  `.\scripts\verify-release-licenses.ps1 -ApkPath .\app\build\outputs\apk\release\net.chanpaca.saegeul.apk`
- `verify-release-identity.ps1` — APK의 제품명·패키지 ID·서명 지문이 선언한 값과 일치하는지 확인한다.
  `.\scripts\verify-release-identity.ps1 -MainApkPath <메인 apk> -HangulApkPath <한글 플러그인 apk> -ExpectedApplicationId net.chanpaca.saegeul -ExpectedProductName Saegeul ...`
- `verify-release-privacy.ps1` — APK 매니페스트·권한이 개인정보 선언과 어긋나지 않는지 확인한다.
  `.\scripts\verify-release-privacy.ps1 -ApkPath <apk 경로>`
- `verify-release-secrets.ps1` — APK 안에 비밀 키·토큰이 섞여 들어가지 않았는지 확인한다.
  `.\scripts\verify-release-secrets.ps1 -PackagePath <apk 경로>`
- `verify-release-aab.ps1` — AAB(Android App Bundle)가 기대한 ABI 세트로 빌드됐는지 확인한다.
  `.\scripts\verify-release-aab.ps1 -AabPath <aab 경로> -ExpectedAbis arm64-v8a,x86_64`
- `verify-release-device.ps1` — 실기기에 APK를 설치해 기동·서명을 확인하는 마지막 게이트다.
  `.\scripts\verify-release-device.ps1 -Serial <adb serial> -MainApkPath <메인 apk> -HangulApkPath <한글 플러그인 apk> ...`
- `verify-release-bundle.ps1` — 위 검증들을 묶어 릴리스 디렉터리 전체(APK·AAB·SBOM·소스 아카이브)를 한 번에 확인한다. `user-release.yml`이 이 스크립트로 최종 게이트를 통과시킨다.
  `.\scripts\verify-release-bundle.ps1 -ReleaseDirectory <dir> -MainApkPath <apk> -MainAabPath <aab> -HangulApkPath <apk> -BuildMetadataPath <json> -SourceArchivePath <tar.gz> -SourceTag <tag> -SbomOutputPath <path> -ProductName Saegeul -ApplicationId net.chanpaca.saegeul -SourceRepositoryUrl <url> -PrivacyPolicyUrl <url> -SourceArchiveUrl <url> -SigningCertificateSha256 <sha256>`
- `generate-release-sbom.ps1` — 릴리스 APK의 SBOM(CycloneDX)을 생성한다.
  `.\scripts\generate-release-sbom.ps1 -ApkPath <apk> -BuildMetadataPath <json> -SourceArchivePath <tar.gz> -SourceRepositoryUrl <url> -SourceArchiveUrl <url> -ProductName Saegeul -ApplicationId net.chanpaca.saegeul -OutputPath <sbom.cdx.json>`
- `create-source-archive.ps1` — 지정한 git ref(태그) 기준으로 서브모듈을 포함한 소스 아카이브(tar.gz)를 만든다. `user-release.yml`이 릴리스마다 실행한다.
  `.\scripts\create-source-archive.ps1 -Ref saegeul-v1.2.3 -OutputDirectory artifacts/source`
- `verify-source-archive.ps1` — 만들어진 소스 아카이브가 해당 git ref의 내용과 정확히 일치하는지 검증한다.
  `.\scripts\verify-source-archive.ps1 -ArchivePath <tar.gz> -Ref saegeul-v1.2.3`
- `verify-public-source-identity.ps1` — 공개 소스가 독립 제품(제품명·앱 ID·저장소 URL·개인정보 정책 URL)으로 일관되게 식별되는지 확인한다. `release-contract.yml`이 커밋마다 실행한다.
  `./scripts/verify-public-source-identity.ps1 -SourceRoot . -ExpectedProductName Saegeul -ExpectedKoreanProductName 새글 -ExpectedApplicationId net.chanpaca.saegeul -ExpectedRepositoryUrl <url> -ExpectedPrivacyPolicyUrl <url>`
- `verify-play-data-safety-contract.ps1` — `docs/independent-fork/play-data-safety-declaration.json` 선언이 코드·문서와 어긋나지 않는지 확인한다. `release-contract.yml`이 커밋마다 실행한다.
  `./scripts/verify-play-data-safety-contract.ps1`

## 한국어 데이터 생성·평가

기본 한국어 팩(다음 어절 예측, 완성 사전, 자모 배열 테이블 등)을 만들고 품질을 검증하는 스크립트다.

- `build-ko-corpus-ngram.py` — FineWeb-2(kor_Hang) 로컬 parquet 샤드에서 어절 단위 n-gram 후보 팩을 만든다.
  `python scripts/build-ko-corpus-ngram.py --shard <parquet> --out-dir <dir>`
- `ko_doc_quality.py` — 위 코퍼스 구축이 가져오는 문서 품질 게이트 모듈(스팸·광고 문서를 걸러낸다). 단독 실행용이 아니라 `build-ko-corpus-ngram.py`가 import한다.
- `merge-ko-casual-layer.py` — 웹 n-gram 팩에 `songys/Chatbot_data` 구어체 층을 섞어 기본 팩을 만든다.
  `python scripts/merge-ko-casual-layer.py --web-pack <dir> --chatbot-csv <csv>`
- `pack-ko-base-assets.py` — 병합된 n-gram 팩을 앱이 읽는 바이너리 에셋으로 변환해 `app/src/main/assets`에 넣는다.
  `python scripts/pack-ko-base-assets.py --pack-dir <dir> --verify`
- `eval-ko-base-pack.py` — 새로 만든 후보 팩을 현재 번들 팩과 비교 평가한다.
  `python scripts/eval-ko-base-pack.py --pack-dir <dir> --unigram <tsv> --bigram <tsv> --trigram <tsv> --test-shard <parquet> --chatbot-csv <csv> --out <report>`
- `eval-ko-everyday-probes.py` — 번들 다음 어절 팩(KONGRAM1)이 일상 대화 프로브 문맥에서 잘 맞는지 확인한다.
  `python scripts/eval-ko-everyday-probes.py <pack1> [pack2 ...] --show`
- `build-ko-base-vocab.py` — 표준 어휘 목록에서 키보드용 기본 어휘 TSV를 만든다.
  `python scripts/build-ko-base-vocab.py --output app/src/main/assets/ko_base_vocab.tsv`
- `generate-korean-completion-dictionary.ps1` — 국립국어원 한국어 학습용 어휘 목록과 프로젝트 자체 대화체 단어를 합쳐 완성 후보 사전을 만든다.
  `.\scripts\generate-korean-completion-dictionary.ps1`
- `generate-korean-dictionary.py` — 고정된 Wiktextract 스냅샷에서 번들 한국어 사전(이진 인덱스)을 생성한다.
  `python scripts/generate-korean-dictionary.py`
- `generate-hangul-keyboard-tables.ps1` — libhangul의 자모 배열 헤더를 받아 코틀린 생성 파일로 변환한다.
  `.\scripts\generate-hangul-keyboard-tables.ps1`
- `korean_continuation_surface.py` — 검증된 접미사만 이어쓰기 결과로 표면화하는 어댑터 모듈. 단독 실행용이 아니라 다른 코드/테스트가 import한다.
- `test_ko_doc_quality.py` / `test_korean_continuation_surface.py` — 위 두 모듈의 단위 테스트. `AGENTS.md`가 지정한 검증 명령이다.
  `python scripts/test_ko_doc_quality.py`, `python scripts/test_korean_continuation_surface.py`

## Play 등록 자료

Google Play 콘솔에 붙여넣거나 올릴 리스팅 자료를 만들고 점검한다.

- `generate-play-listing-assets.py` — 새글 브랜드 마크에서 Play 리스팅 그래픽(아이콘·피처 그래픽 등)을 생성한다.
  `python scripts/generate-play-listing-assets.py`
- `copy-emu-play-screenshots.py` — 에뮬레이터로 찍은 캡처를 Play 리스팅 스크린샷 규격(24비트 PNG)으로 변환해 복사한다.
  `python scripts/copy-emu-play-screenshots.py`
- `inspect-play-listing-assets.py` — 리스팅 PNG들의 크기·모드·알파 채널 유무를 점검한다.
  `python scripts/inspect-play-listing-assets.py`
- `play-listing-copy.py` — 로케일별 리스팅 제목·짧은 설명·전체 설명을 콘솔에 출력해 Play 콘솔에 붙여넣기 쉽게 한다.
  `python scripts/play-listing-copy.py`

## 기기·레드팀

실기기/에뮬레이터에 붙어 동작을 측정하거나 회귀를 공격적으로 재현한다.

- `redteam-extreme-accuracy.ps1` — 에뮬레이터·기기에서 한글 조합 정확도를 반복 입력으로 공격적으로 재현·점검한다.
  `.\scripts\redteam-extreme-accuracy.ps1 -Serial emulator-5554 -Iterations 5`
- `measure-gemma-utility.ps1` — 실기기에서 온디바이스 Gemma 추천의 유용성(수용률 등)을 베이스라인과 비교 측정한다.
  `.\scripts\measure-gemma-utility.ps1 -DeviceSerial <adb serial> -OutputDirectory <dir> -BaselineDirectory <dir>`

## 기타

- `publish-wiki.py` — `docs/wiki/`의 마크다운을 GitHub Wiki 저장소(`saegeul.wiki.git`)로 그대로 push한다.
  `python scripts/publish-wiki.py`
