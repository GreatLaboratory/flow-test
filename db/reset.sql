-- 운영자 수동 실행용 데모 리셋 (Flyway 밖). 감사 기록은 남지 않는다.
BEGIN;
DELETE FROM extension WHERE type = 'CUSTOM';
UPDATE extension SET blocked = false WHERE type = 'FIXED';
COMMIT;
