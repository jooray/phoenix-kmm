import SwiftUI
import AVFoundation
import PhoenixShared

import UIKit

struct ScanView: MVIView {
    typealias Model = Scan.Model
    typealias Intent = Scan.Intent

    @Binding var isShowing: Bool

    @StateObject var toast = Toast()

    var body: some View {
        ZStack {
            mvi(onModel: { model in
                if model is Scan.ModelSending {
                    isShowing = false
                } else if model is Scan.ModelBadRequest {
                    toast.toast(text: "Unexpected request format!")
                }
            }) { model, intent in
                view(model: model, intent: intent)
            }
            toast.view()
        }
    }

    @ViewBuilder
    func view(model: Scan.Model, intent: @escaping IntentReceiver) -> some View {
        switch model {
        case _ as Scan.ModelReady, _ as Scan.ModelBadRequest: ReadyView(intent: intent)
        case let m as Scan.ModelValidate: ValidateView(model: m, intent: intent)
        case let m as Scan.ModelSending: SendingView(model: m)
        default:
            fatalError("Unknown model \(model)")
        }
    }

    struct ReadyView: View {
        let intent: IntentReceiver

        var body: some View {
            VStack {
                Spacer()
                Text("Scan a QR code")
                        .padding()
                        .font(.title2)

                    QrCodeScannerView().found { request in
                            print(request)
                            intent(Scan.IntentParse(request: request))
                        }
                        .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                        .stroke(Color.gray, lineWidth: 4)
                        )
                        .padding()

                Divider()
                        .padding([.leading, .trailing])

                Button {
                    if let request = UIPasteboard.general.string {
                        intent(Scan.IntentParse(request: request))
                    }
                } label: {
                    Image(systemName: "arrow.right.doc.on.clipboard")
                    Text("Paste from clipboard")
                            .font(.title2)
                }
                        .disabled(!UIPasteboard.general.hasStrings)
                        .padding()
                Spacer()
            }
                    .navigationBarTitle("Payment request", displayMode: .inline)

        }
    }

    struct ValidateView: View {
        let model: Scan.ModelValidate

        let intent: IntentReceiver

        var body: some View {
            VStack {
                Text("\(model.amountMsat ?? 0) msat")
                        .font(.title)
                        .padding()

                Text(model.requestDescription ?? "")
                        .padding()

                Button {
                    intent(Scan.IntentSend(request: model.request, amountMsat: model.amountMsat?.int64Value ?? 0))
                } label: {
                    Text("Pay")
                            .font(.title)
                            .padding()
                }
            }
                    .navigationBarTitle("", displayMode: .inline)
        }
    }

    struct SendingView: View {
        let model: Scan.ModelSending

        var body: some View {
            VStack {
                Text("Sending \(model.amountMsat) msat...")
                        .font(.title)
                        .padding()

                Text(model.requestDescription ?? "")
                        .padding()
            }
                    .navigationBarTitle("Sending payment", displayMode: .inline)
        }
    }
}


class ScanView_Previews: PreviewProvider {

    static let mockModel = Scan.ModelReady()

    static var previews: some View {
        mockView(ScanView(isShowing: .constant(true))) { $0.scanModel = ScanView_Previews.mockModel }
                .previewDevice("iPhone 11")
    }

    #if DEBUG
    @objc class func injected() {
        UIApplication.shared.windows.first?.rootViewController = UIHostingController(rootView: previews)
    }
    #endif
}
