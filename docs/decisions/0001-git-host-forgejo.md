# 0001. git 호스트는 Forgejo

- 상태: 채택
- 날짜: 2026-10-09

## 맥락

저장소의 문서와 도구 일부(이슈 템플릿, 위키 내보내기, 배지, 릴리스 게이트의 저장소 주소)가 GitHub를 전제로 쓰여 있다. 실제 git 호스트는 Forgejo(`git.chanpaca.net`)다.

## 결정

git 호스트는 Forgejo다. GitHub 전제 문서·도구는 정리한다. 새 주소를 받기 전까지 동작 중인 게이트(`release-contract.yml`의 `-ExpectedRepositoryUrl` 등)는 건드리지 않는다.

## 결과

- GitHub 전용 이슈 템플릿(`.github/ISSUE_TEMPLATE/`)을 지웠다.
- 위키 내보내기(`scripts/publish-wiki.py`), README 배지·링크, origin 원격, `-ExpectedRepositoryUrl`은 Forgejo 주소를 받은 뒤 바꾼다.
- 근거: `docs/engineering/cleanup-2026-10.md`(G2와 "결정이 필요한 것").
- 2026-10-11: 사이트 배포를 Forgejo Actions(`.forgejo/workflows/deploy-site.yml`, 러너 `docker`)로 옮기고 `.github/workflows/pages.yml`을 지웠다. Forgejo 저장소는 비공개 `yunchan/saegeul`, 원격 이름은 `forgejo`다. 앱 릴리스 CI(`user-release.yml`)와 릴리스 게시는 아직 GitHub에 둔다.
