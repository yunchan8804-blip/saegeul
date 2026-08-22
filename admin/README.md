# AVENUE Control

새글 키보드의 앱 내 광고 AVENUE와 앱 밖 유료 사용자 획득 캠페인, 정책 위험, 수익·지출 대사를 관리하는 내부 운영 UI다.

현재 구현은 **로컬 운영 데모**다. 모든 수치와 실시간 이벤트는 결정론적 예시이며 광고 SDK, AdMob 계정, 운영 API와 연결돼 있지 않다. 브라우저 `localStorage`에 AVENUE 편집 결과만 저장한다.

## 실행

```powershell
cd D:\workspace\fcitx5-android\admin
npm install
npm run dev
```

검증:

```powershell
npm run test
npm run check
npm run build
```

## 화면

- 관제 대시보드
- AVENUE 추가·편집과 정책 미리보기
- 광고 네트워크 상태
- 예상액·확정액·입금 대사
- 자연어 홍보 캠페인 명령과 플랫폼 적합성 드라이런
- Play·개인정보·측정·계정·소재·예산·2인 승인 가드
- 첫 활성 사용자 CAC, D7, 순 기여 효과 분석
- 전역 킬 스위치와 정책 가드레일
- 감사 로그
- 일시정지 가능한 실시간 데모 이벤트

## 운영 전환

앱 수익화는 `../docs/ad-monetization-avenue-operations.md`, 앱 홍보 오케스트레이션은 `../docs/app-promotion-campaign-orchestration.md`를 따른다. 현재 개인정보 문서는 광고 SDK와 분석 추적 라이브러리가 없다고 약속하므로 해당 출시 게이트를 통과하기 전에는 프로덕션 광고나 측정을 연결하면 안 된다.
