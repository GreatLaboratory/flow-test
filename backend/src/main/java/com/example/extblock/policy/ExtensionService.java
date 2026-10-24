package com.example.extblock.policy;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.extblock.common.ApiException;
import com.example.extblock.common.ClientInfo;
import com.example.extblock.common.ErrorCode;
import com.example.extblock.config.AppProperties;

/**
 * 정책 변경 트랜잭션 3개 (§6-3). 감사 INSERT는 변경과 같은 트랜잭션
 * → "변경은 됐는데 기록이 없는" 상태가 원천적으로 불가능.
 */
@Service
public class ExtensionService {

	private final ExtensionRepository extensions;

	private final PolicyAuditRepository audit;

	private final AppProperties props;

	public ExtensionService(ExtensionRepository extensions, PolicyAuditRepository audit, AppProperties props) {
		this.extensions = extensions;
		this.audit = audit;
		this.props = props;
	}

	public ExtensionView getAll() {
		List<ExtensionRow> rows = extensions.findAll();
		List<FixedView> fixed = rows.stream().filter(ExtensionRow::isFixed).map(FixedView::from).toList();
		List<CustomView> custom = rows.stream().filter(row -> !row.isFixed()).map(CustomView::from).toList();
		return new ExtensionView(fixed, custom, props.policy().customLimit(), props.upload().maxFileSize().toBytes());
	}

	@Transactional
	public FixedView setFixedBlocked(long id, boolean blocked, ClientInfo client) {
		int changed = extensions.updateFixedBlocked(id, blocked);
		ExtensionRow row = extensions.findFixedById(id).orElseThrow(() -> new ApiException(ErrorCode.EXTENSION_NOT_FOUND));
		if (changed == 1) { // 0이면 이미 그 상태 → no-op, 감사도 생략(반복 클릭 잡음 제거)
			audit.insert(blocked ? AuditAction.FIXED_BLOCK : AuditAction.FIXED_UNBLOCK, row.name(), client);
		}
		return FixedView.from(row);
	}

	@Transactional
	public CustomView addCustom(String rawName, ClientInfo client) {
		String name = ExtensionNormalizer.normalize(rawName);

		// 잠금 아래에서는 사전 조회 결과가 정확하다 → 친절한 에러 메시지를 줄 수 있다
		extensions.lockTable();
		extensions.findByName(name).ifPresent(existing -> {
			throw conflict(existing.isFixed() ? ErrorCode.FIXED_EXTENSION_CONFLICT : ErrorCode.DUPLICATE_EXTENSION, name);
		});
		int limit = props.policy().customLimit();
		if (extensions.countCustom() >= limit) {
			ErrorCode code = ErrorCode.CUSTOM_LIMIT_EXCEEDED;
			throw new ApiException(code, code.defaultMessage().replace("{limit}", String.valueOf(limit)), null);
		}

		ExtensionRow row;
		try {
			row = extensions.insertCustom(name);
		}
		catch (DuplicateKeyException e) { // 최종 방어선: 잠금을 거치지 않은 쓰기(직접 SQL 등)와의 충돌
			throw conflict(ErrorCode.DUPLICATE_EXTENSION, name);
		}
		audit.insert(AuditAction.CUSTOM_ADD, name, client);
		return CustomView.from(row);
	}

	/** 대상이 없어도 성공(멱등). id 기준이라 오래된 탭이 재추가된 같은 이름을 지우지 않는다. */
	@Transactional
	public void deleteCustom(long id, ClientInfo client) {
		extensions.deleteCustom(id).ifPresent(name -> audit.insert(AuditAction.CUSTOM_DELETE, name, client));
	}

	private static ApiException conflict(ErrorCode code, String name) {
		return new ApiException(code, code.defaultMessage().replace("{name}", name), null);
	}

}
