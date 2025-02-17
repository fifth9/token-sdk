package com.r3.corda.lib.tokens.workflows.internal.selection

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStateAndRefs
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.utilities.sortByStateRefAscending
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountCriteria
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Amount.Companion.sumOrThrow
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.AbstractParty
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.DEFAULT_PAGE_NUM
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.millis
import net.corda.core.utilities.toNonEmptySet
import java.util.*

/**
 * TokenType selection using Hibernate. It uses roughly the same logic that the coin selection algorithm used in
 * AbstractCoinSelection within the finance module. The only difference is that now there are not specific database
 * implementations, instead hibernate is used for an agnostic approach.
 *
 * When calling [attemptSpend] there is the option to pass in a custom [QueryCriteria] and [Sort]. The default behaviour
 * is to order all states by [StateRef] and query for a specific token type. The default behaviour is probably not very
 * efficient but the behaviour can be customised if necessary.
 *
 * This is only really here as a stopgap solution until in-memory token selection is implemented.
 *
 * @param services for performing vault queries.
 */
class TokenSelection(
        val services: ServiceHub,
        private val maxRetries: Int = 8,
        private val retrySleep: Int = 100,
        private val retryCap: Int = 2000
) {

    companion object {
        val logger = contextLogger()
    }

    /** Queries for held token amounts with the specified token to the specified requiredAmount. */
    private fun <T : TokenType> executeQuery(
            requiredAmount: Amount<T>,
            lockId: UUID,
            additionalCriteria: QueryCriteria,
            sorter: Sort,
            stateAndRefs: MutableList<StateAndRef<FungibleToken>>,
            pageSize: Int = 200,
            softLockingType: QueryCriteria.SoftLockingType = QueryCriteria.SoftLockingType.UNLOCKED_ONLY
    ): Boolean {
        // Didn't need to select any tokens.
        if (requiredAmount.quantity == 0L) {
            return false
        }

        // Enrich QueryCriteria with additional default attributes (such as soft locks).
        // We only want to return RELEVANT states here.
        val baseCriteria = QueryCriteria.VaultQueryCriteria(
                contractStateTypes = setOf(FungibleToken::class.java),
                softLockingCondition = QueryCriteria.SoftLockingCondition(softLockingType, listOf(lockId)),
                relevancyStatus = Vault.RelevancyStatus.RELEVANT,
                status = Vault.StateStatus.UNCONSUMED
        )

        var pageNumber = DEFAULT_PAGE_NUM
        var claimedAmount = 0L

        do {
            val pageSpec = PageSpecification(pageNumber = pageNumber, pageSize = pageSize)
            val results: Vault.Page<FungibleToken> = services.vaultService.queryBy(baseCriteria.and(additionalCriteria), pageSpec, sorter)

            for (state in results.states) {
                stateAndRefs += state
                claimedAmount += state.state.data.amount.quantity
                if (claimedAmount >= requiredAmount.quantity) {
                    break
                }
            }

            pageNumber++
        } while (claimedAmount < requiredAmount.quantity && (pageSpec.pageSize * (pageNumber - 1)) <= results.totalStatesAvailable)

        val claimedAmountWithToken = Amount(claimedAmount, requiredAmount.token)
        // No tokens available.
        if (stateAndRefs.isEmpty()) return false
        // There were not enough tokens available.
        if (claimedAmountWithToken < requiredAmount) {
            logger.trace("TokenType selection requested $requiredAmount but retrieved $claimedAmountWithToken with state refs: ${stateAndRefs.map { it.ref }}")
            return false
        }

        // We picked enough tokensToIssue, so softlock and go.
        logger.trace("TokenType selection for $requiredAmount retrieved ${stateAndRefs.count()} states totalling $claimedAmountWithToken: $stateAndRefs")
        services.vaultService.softLockReserve(lockId, stateAndRefs.map { it.ref }.toNonEmptySet())
        return true
    }


    /**
     * Attempt spend of [requiredAmount] of [FungibleToken] T. Returns states that cover given amount. Notice that this
     * function doesn't calculate change. If query criteria is not specified then only held token amounts are used.
     *
     * Use [QueryUtilities.tokenAmountWithIssuerCriteria] to specify issuer.
     * Calling attemptSpend multiple time with the same lockId will return next unlocked states.
     *
     * @return List of [FungibleToken]s that satisfy the amount to spend, empty list if none found.
     */
    @Suspendable
    fun attemptSpend(
            requiredAmount: Amount<TokenType>,
            lockId: UUID,
            additionalCriteria: QueryCriteria = tokenAmountCriteria(requiredAmount.token),
            sorter: Sort = sortByStateRefAscending(),
            pageSize: Int = 200
    ): List<StateAndRef<FungibleToken>> {
        val stateAndRefs = mutableListOf<StateAndRef<FungibleToken>>()
        for (retryCount in 1..maxRetries) {
            // TODO: Need to specify exactly why it fails. Locked states or literally _no_ states!
            // No point in retrying if there will never be enough...
            if (!executeQuery(requiredAmount, lockId, additionalCriteria, sorter, stateAndRefs, pageSize)) {
                logger.warn("TokenType selection failed on attempt $retryCount.")
                // TODO: revisit the back off strategy for contended spending.
                if (retryCount != maxRetries) {
                    stateAndRefs.clear()
                    val durationMillis = (minOf(retrySleep.shl(retryCount), retryCap / 2) * (1.0 + Math.random())).toInt()
                    FlowLogic.sleep(durationMillis.millis)
                } else {
                    logger.warn("Insufficient spendable states identified for $requiredAmount.")
                    // TODO: Create new exception type.
                    throw IllegalStateException("Insufficient spendable states identified for $requiredAmount.")
                }
            } else {
                break
            }
        }
        return stateAndRefs.toList()
    }

    /**
     * Generate move of [FungibleToken] T to tokenHolders specified in [PartyAndAmount]. Each party will receive amount
     * defined by [partyAndAmounts]. If query criteria is not specified then only held token amounts are used. Use
     * [QueryUtilities.tokenAmountWithIssuerCriteria] to specify issuer. This function mutates [builder] provided as
     * parameter.
     *
     * @return [TransactionBuilder] and list of all owner keys used in the input states that are going to be moved.
     */
    @Suspendable
    fun generateMove(
            lockId: UUID,
            partyAndAmounts: List<PartyAndAmount<TokenType>>,
            changeHolder: AbstractParty,
            queryCriteria: QueryCriteria? = null
    ): Pair<List<StateAndRef<FungibleToken>>, List<FungibleToken>> {
        // Grab some tokens from the vault and soft-lock.
        // Only supports moves of the same token instance currently.
        // TODO Support spends for different token types, different instances of the same type.
        // The way to do this will be to perform a query for each token type. If there are multiple token types then
        // just do all the below however many times is necessary.
        val totalRequired = partyAndAmounts.map { it.amount }.sumOrThrow()
        val additionalCriteria = queryCriteria ?: tokenAmountCriteria(totalRequired.token)
        val acceptableStates = attemptSpend(totalRequired, lockId, additionalCriteria)
        require(acceptableStates.isNotEmpty()) {
            "No states matching given criteria to generate move."
        }

        // Check that the change identity belongs to the node that called generateMove.
        val ownerId = services.identityService.wellKnownPartyFromAnonymous(changeHolder)
        check(ownerId != null && services.myInfo.isLegalIdentity(ownerId)) {
            "Owner of the change: $changeHolder is not the identity that belongs to the node."
        }

        // Now calculate the output states. This is complicated by the fact that a single payment may require
        // multiple output states, due to the need to keep states separated by issuer. We start by figuring out
        // how much we've gathered for each issuer: this map will keep track of how much we've used from each
        // as we work our way through the payments.
        val tokensGroupedByIssuer = acceptableStates.groupBy { it.state.data.amount.token }
        val remainingTokensFromEachIssuer = tokensGroupedByIssuer.mapValues { (_, value) ->
            value.map { (state) -> state.data.amount }.sumOrThrow()
        }.toList().toMutableList()

        // TODO: This assumes there is only ever ONE notary. In the future we need to deal with notary change.
        check(acceptableStates.map { it.state.notary }.toSet().size == 1) {
            "States selected have different notaries. For now we don't support notary change, it should be performed beforehand."
        }

        // Calculate the list of output states making sure that the
        val outputStates = mutableListOf<FungibleToken>()
        for ((party, paymentAmount) in partyAndAmounts) {
            var remainingToPay = paymentAmount.quantity
            while (remainingToPay > 0) {
                val (token, remainingFromCurrentIssuer) = remainingTokensFromEachIssuer.last()
                val delta = remainingFromCurrentIssuer.quantity - remainingToPay
                when {
                    delta > 0 -> {
                        // The states from the current issuer more than covers this payment.
                        outputStates += FungibleToken(Amount(remainingToPay, token), party)
                        remainingTokensFromEachIssuer[remainingTokensFromEachIssuer.lastIndex] = Pair(token, Amount(delta, token))
                        remainingToPay = 0
                    }
                    delta == 0L -> {
                        // The states from the current issuer exactly covers this payment.
                        outputStates += FungibleToken(Amount(remainingToPay, token), party)
                        remainingTokensFromEachIssuer.removeAt(remainingTokensFromEachIssuer.lastIndex)
                        remainingToPay = 0
                    }
                    delta < 0 -> {
                        // The states from the current issuer don't cover this payment, so we'll have to use >1 output
                        // state to cover this payment.
                        outputStates += FungibleToken(remainingFromCurrentIssuer, party)
                        remainingTokensFromEachIssuer.removeAt(remainingTokensFromEachIssuer.lastIndex)
                        remainingToPay -= remainingFromCurrentIssuer.quantity
                    }
                }
            }
        }

        // Generate the change states.
        remainingTokensFromEachIssuer.forEach { (_, amount) ->
            outputStates += FungibleToken(amount, changeHolder)
        }

        return Pair(acceptableStates, outputStates)
    }

    // Modifies builder in place. All checks for exit states should have been done before.
    // For example we assume that existStates have same issuer.
    @Suspendable
    fun generateExit(
            exitStates: List<StateAndRef<FungibleToken>>,
            amount: Amount<TokenType>,
            changeOwner: AbstractParty
    ): Pair<List<StateAndRef<FungibleToken>>, FungibleToken?> {
        // Choose states to cover amount - return ones used, and change output
        val changeOutput = change(exitStates, amount, changeOwner)
        return Pair(exitStates, changeOutput)
    }

    private fun change(
            exitStates: List<StateAndRef<FungibleToken>>,
            amount: Amount<TokenType>,
            changeOwner: AbstractParty
    ): FungibleToken? {
        val assetsSum = exitStates.sumTokenStateAndRefs()
        val difference = assetsSum - amount.issuedBy(exitStates.first().state.data.amount.token.issuer)
        check(difference.quantity >= 0) {
            "Sum of exit states should be equal or greater than the amount to exit."
        }
        return if (difference.quantity == 0L) {
            null
        } else {
            difference heldBy changeOwner
        }
    }
}