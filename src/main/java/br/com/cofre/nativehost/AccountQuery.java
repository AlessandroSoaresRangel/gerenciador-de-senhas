package br.com.cofre.nativehost;

import br.com.cofre.domain.AccountMatch;

import java.util.List;

@FunctionalInterface
public interface AccountQuery {
    List<AccountMatch> listMatches(String origin) throws Exception;
}
