CREATE TABLE extension (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name       VARCHAR(20) NOT NULL UNIQUE,
  type       VARCHAR(10) NOT NULL CHECK (type IN ('FIXED','CUSTOM')),
  blocked    BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT extension_name_format CHECK (name ~ '^[a-z0-9]{1,20}$'),
  CONSTRAINT custom_always_blocked CHECK (type = 'FIXED' OR blocked)
);

CREATE TABLE policy_audit (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  action         VARCHAR(20) NOT NULL
                 CHECK (action IN ('FIXED_BLOCK','FIXED_UNBLOCK','CUSTOM_ADD','CUSTOM_DELETE')),
  extension_name VARCHAR(20) NOT NULL,
  client_ip      VARCHAR(45),
  user_agent     VARCHAR(512),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
