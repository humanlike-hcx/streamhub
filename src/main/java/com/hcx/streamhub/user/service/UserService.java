package com.hcx.streamhub.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.common.ErrorCode;
import com.hcx.streamhub.user.entity.User;
import com.hcx.streamhub.user.enums.UserStatus;
import com.hcx.streamhub.user.mapper.UserMapper;

@Service
public class UserService {

	private final UserMapper userMapper;

	public UserService(UserMapper userMapper) {
		this.userMapper = userMapper;
	}

	public User findByUsername(String username) {
		return userMapper.selectOne(new LambdaQueryWrapper<User>()
				.eq(User::getUsername, username)
				.last("LIMIT 1"));
	}

	public User getById(Long userId) {
		User user = userMapper.selectById(userId);
		if (user == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return user;
	}

	public boolean existsByUsername(String username) {
		return findByUsername(username) != null;
	}

	@Transactional
	public User createUser(String username, String passwordHash, String nickname) {
		if (existsByUsername(username)) {
			throw new BusinessException(ErrorCode.USERNAME_EXISTS);
		}

		User user = new User();
		user.setUsername(username);
		user.setPasswordHash(passwordHash);
		user.setNickname(nickname);
		user.setStatus(UserStatus.ACTIVE.name());
		userMapper.insert(user);
		return user;
	}
}
