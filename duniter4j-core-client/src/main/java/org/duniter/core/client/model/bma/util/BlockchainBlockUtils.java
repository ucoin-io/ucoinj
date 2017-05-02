package org.duniter.core.client.model.bma.util;

/*-
 * #%L
 * Duniter4j :: Core Client API
 * %%
 * Copyright (C) 2014 - 2017 EIS
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import jnr.ffi.annotations.In;
import org.duniter.core.client.model.bma.BlockchainBlock;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Created by blavenie on 26/04/17.
 */
public final class BlockchainBlockUtils {

    public static final Pattern TX_UNLOCK_PATTERN = Pattern.compile("([0-9]+):SIG\\(([^)]+)\\)");
    public static final Pattern TX_OUTPUT_PATTERN = Pattern.compile("([0-9]+):([0-9]+):SIG\\(([^)]+)\\)");

    private BlockchainBlockUtils () {
        // helper class
    }

    public static BigInteger getTxAmount(BlockchainBlock block) {
        BigInteger result = BigInteger.valueOf(0l);
        Arrays.stream(block.getTransactions())
                .forEach(tx -> result.add(BigInteger.valueOf(getTxAmount(tx))));
        return result;
    }

    public static long getTxAmount(final BlockchainBlock.Transaction tx) {

        final Map<Integer, Integer> inputsByIssuer = Maps.newHashMap();
        Arrays.stream(tx.getUnlocks())
                .map(TX_UNLOCK_PATTERN::matcher)
                .filter(Matcher::matches)
                .forEach(matcher -> inputsByIssuer.put(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)))
                );

        return IntStream.range(0, tx.getIssuers().length)
                .mapToLong(i -> {
                    final String issuer = tx.getIssuers()[i];

                    long inputSum = IntStream.range(0, tx.getInputs().length)
                            .filter(j -> i == inputsByIssuer.get(j))
                            .mapToObj(j -> tx.getInputs()[j])
                            .map(input -> input.split(":"))
                            .filter(inputParts -> inputParts.length > 2)
                            .mapToLong(inputParts -> powBase(Long.parseLong(inputParts[0]), Integer.parseInt(inputParts[1])))
                            .sum();

                    long outputSum = Arrays.stream(tx.getOutputs())
                            .map(TX_OUTPUT_PATTERN::matcher)
                            .filter(Matcher::matches)
                            .filter(matcher -> issuer.equals(matcher.group(3)))
                            .mapToLong(matcher -> powBase(Long.parseLong(matcher.group(1)), Integer.parseInt(matcher.group(2))))
                            .sum();

                    return (inputSum - outputSum);
                })
                .sum();
    }


    private static long powBase(long amount, int unitbase) {
        if (unitbase == 0) return amount;
        return amount * (long)Math.pow(10, unitbase);
    }
}
