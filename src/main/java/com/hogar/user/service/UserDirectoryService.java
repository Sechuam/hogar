package com.hogar.user.service;

import com.hogar.user.domain.User;
import com.hogar.user.dto.UserSummary;
import com.hogar.user.repository.UserRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserDirectoryService implements UserDirectory {

    private final UserRepository users;

    UserDirectoryService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findSummaries(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds).stream()
                .map(UserDirectoryService::toSummary)
                .collect(Collectors.toUnmodifiableMap(UserSummary::id, Function.identity()));
    }

    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getDisplayName(), user.getEmail());
    }
}
