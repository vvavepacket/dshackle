package io.emeraldpay.dshackle.rpc

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.Common
import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.dshackle.test.UpstreamsMock
import io.emeraldpay.dshackle.upstream.Upstreams
import io.emeraldpay.grpc.Chain
import io.infinitape.etherjar.domain.Address
import io.infinitape.etherjar.domain.BlockHash
import io.infinitape.etherjar.rpc.RpcClient
import io.infinitape.etherjar.rpc.json.BlockJson
import reactor.core.publisher.Mono
import reactor.core.publisher.TopicProcessor
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import spock.lang.Specification

import java.time.Duration

class TrackAddressSpec extends Specification {

    def chain = Common.ChainRef.CHAIN_ETHEREUM
    def address1 = "0xe2c8fa8120d813cd0b5e6add120295bf20cfa09f"
    def address1Proto = Common.SingleAddress.newBuilder()
            .setAddress(address1)
    def etherAsset = Common.Asset.newBuilder()
            .setChain(chain)
            .setCode("ETHER")


    def "get balance"() {
        setup:
        def req = BlockchainOuterClass.BalanceRequest.newBuilder()
                .setAsset(etherAsset)
                .setAddress(Common.AnyAddress.newBuilder().setAddressSingle(address1Proto).build())
                .build()
        def exp = BlockchainOuterClass.AddressBalance.newBuilder()
            .setAddress(address1Proto)
            .setAsset(etherAsset)
            .setBalance("1234567890")
            .build()

        def apiMock = TestingCommons.api(Stub(RpcClient))
        def upstreamMock = TestingCommons.upstream(apiMock)
        Upstreams upstreams = new UpstreamsMock(Chain.ETHEREUM, upstreamMock)
        TrackAddress trackAddress = new TrackAddress(upstreams, Schedulers.immediate())
        trackAddress.init()

        apiMock.answer("eth_getBalance", ["0xe2c8fa8120d813cd0b5e6add120295bf20cfa09f", "latest"], "0x499602D2")
        when:
        def flux = trackAddress.getBalance(Mono.just(req))
        then:
        StepVerifier.create(flux)
            .expectNext(exp)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
        !trackAddress.isTracked(Chain.ETHEREUM, Address.from(address1))
    }

    def "recheck address after each block"() {
        setup:
        def req = BlockchainOuterClass.BalanceRequest.newBuilder()
                .setAsset(etherAsset)
                .setAddress(Common.AnyAddress.newBuilder().setAddressSingle(address1Proto).build())
                .build()
        def exp1 = BlockchainOuterClass.AddressBalance.newBuilder()
                .setAddress(address1Proto)
                .setAsset(etherAsset)
                .setBalance("1234567890")
                .build()
        def exp2 = BlockchainOuterClass.AddressBalance.newBuilder()
                .setAddress(address1Proto)
                .setAsset(etherAsset)
                .setBalance("65432")
                .build()

        def block2 = new BlockJson().with {
            it.number = 1
            it.totalDifficulty = 100
            it.hash = BlockHash.from("0xa0e65cbc1b52a8ca60562112c6060552d882f16f34a9dba2ccdc05c0a6a27c22")
            return it
        }

        def blocksBus = TopicProcessor.create()
        def apiMock = TestingCommons.api(Stub(RpcClient))
        def upstreamMock = TestingCommons.upstream(apiMock)
        Upstreams upstreams = new UpstreamsMock(Chain.ETHEREUM, upstreamMock)
        TrackAddress trackAddress = new TrackAddress(upstreams, Schedulers.immediate())
        trackAddress.init()

        apiMock.answerOnce("eth_getBalance", ["0xe2c8fa8120d813cd0b5e6add120295bf20cfa09f", "latest"], "0x499602D2")
        apiMock.answerOnce("eth_getBalance", ["0xe2c8fa8120d813cd0b5e6add120295bf20cfa09f", "latest"], "0xff98")
        when:
        def flux = trackAddress.subscribe(Mono.just(req))
        then:
        StepVerifier.create(flux)
                .expectNext(exp1)
                .then {
                    assert trackAddress.isTracked(Chain.ETHEREUM, Address.from(address1))
                }
                .then {
                    upstreamMock.nextBlock(block2)
                }
                .expectNext(exp2)
                .thenCancel()
                .verify(Duration.ofSeconds(3))
        Thread.sleep(50)
        !trackAddress.isTracked(Chain.ETHEREUM, Address.from(address1))
    }
}