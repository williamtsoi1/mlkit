/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.md.productsearch

import android.content.Context
import android.util.Log
import com.android.volley.RequestQueue
import com.android.volley.toolbox.Volley
import com.google.android.gms.tasks.Tasks
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.vision.v1.Vision
import com.google.api.services.vision.v1.VisionRequestInitializer
import com.google.api.services.vision.v1.model.*
import com.google.cloud.vision.v1.ProductSetName
import com.google.mlkit.md.BuildConfig
import com.google.mlkit.md.objectdetection.DetectedObjectInfo
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList


/** A fake search engine to help simulate the complete work flow.  */
class SearchEngine(context: Context) {

    private val searchRequestQueue: RequestQueue = Volley.newRequestQueue(context)
    private val requestCreationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    fun search(
        detectedObject: DetectedObjectInfo,
        listener: (detectedObject: DetectedObjectInfo, productList: List<Product>) -> Unit
    ) {
        // Crops the object image out of the full image is expensive, so do it off the UI thread.
        Tasks.call<BatchAnnotateImagesResponse>(requestCreationExecutor, Callable { createRequest(detectedObject) })
            .addOnSuccessListener { response ->
                val productList = ArrayList<Product>()
                try {
                    val similarProducts: List<Result> = response.responses[0].productSearchResults.results

                    for (similarProduct in similarProducts) {
                        val score = similarProduct.score.toString()
                        val title = similarProduct.product.displayName
                        productList.add(Product(similarProduct.image, title, score))
                    }
                }
                catch (ex: java.lang.Exception) {
                    Log.e(TAG, "Failed to get productSearchResults", ex)
                    for (i in 0..7) {
                        productList.add(
                                Product(/* imageUrl= */"", "Product title $i", "Product subtitle $i")
                        )
                    }
                }
                finally {
                    listener.invoke(detectedObject, productList)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to create product search request!", e)
                // Remove the below dummy code after your own product search backed hooked up.
                val productList = ArrayList<Product>()
                for (i in 0..7) {
                    productList.add(
                        Product(/* imageUrl= */"", "Product title $i", "Product subtitle $i")
                    )
                }
                listener.invoke(detectedObject, productList)
            }
    }

    fun shutdown() {
        searchRequestQueue.cancelAll(TAG)
        requestCreationExecutor.shutdown()
    }

    companion object {
        private const val TAG = "SearchEngine"
        private const val CLOUD_VISION_API_KEY = BuildConfig.GOOGLE_CLOUD_API_KEY
        private const val GOOGLE_CLOUD_PROJECT = BuildConfig.GOOGLE_CLOUD_PROJECT
        private const val GOOGLE_CLOUD_REGION = BuildConfig.GOOGLE_CLOUD_REGION
        private const val GOOGLE_CLOUD_VISION_PRODUCT_SET = BuildConfig.GOOGLE_CLOUD_VISION_PRODUCT_SET
        private const val GOOGLE_CLOUD_VISION_PRODUCT_CATEGORY = BuildConfig.GOOGLE_CLOUD_VISION_PRODUCT_CATEGORY

        @Throws(Exception::class)
        private fun createRequest(searchingObject: DetectedObjectInfo): BatchAnnotateImagesResponse {

            val builder = Vision.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), null)
            builder.setVisionRequestInitializer(VisionRequestInitializer(CLOUD_VISION_API_KEY))
            val vision: Vision = builder.build()

            val batchAnnotateImagesRequest = BatchAnnotateImagesRequest()
            batchAnnotateImagesRequest.requests = object : java.util.ArrayList<AnnotateImageRequest?>() {
                init {
                    val annotateImageRequest = AnnotateImageRequest()

                    // Add the image
                    val base64EncodedImage = Image()
                    val objectImageData = searchingObject.imageData
                            ?: throw Exception("Failed to get object image data!")

                    // Base64 encode the JPEG
                    base64EncodedImage.encodeContent(objectImageData)
                    annotateImageRequest.image = base64EncodedImage

                    // add the features we want
                    annotateImageRequest.features = object : java.util.ArrayList<Feature>() {
                        init {
                            val productSearch = Feature()
                            productSearch.type = "PRODUCT_SEARCH"
                            productSearch.maxResults = 5
                            add(productSearch)
                        }
                    }

                    // add the image context
                    val productSetPath = ProductSetName.format(GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_REGION, GOOGLE_CLOUD_VISION_PRODUCT_SET)
                    val imageContext = ImageContext()
                    val productSearchParams = ProductSearchParams()
                    productSearchParams.productSet = productSetPath
                    val productSearchCategories = ArrayList<String>()
                    productSearchCategories.add(GOOGLE_CLOUD_VISION_PRODUCT_CATEGORY)
                    productSearchParams.productCategories = productSearchCategories

                    // add bounding box to request
                    val boundingPoly = BoundingPoly()
//                    val box = searchingObject.boundingBox
//                    val vertex1 = Vertex()
//                    vertex1.x = box.left
//                    vertex1.y = box.top
//                    val vertex2 = Vertex()
//                    vertex2.x = box.right
//                    vertex2.y = box.top
//                    val vertex3 = Vertex()
//                    vertex3.x = box.right
//                    vertex3.y = box.bottom
//                    val vertex4 = Vertex()
//                    vertex4.x = box.left
//                    vertex4.y = box.bottom
//
//                    boundingPoly.vertices = object : java.util.ArrayList<Vertex>() {
//                        init {
//                            add(vertex1)
//                            add(vertex2)
//                            add(vertex3)
//                            add(vertex4)
//                        }
//                    }
                    productSearchParams.boundingPoly = boundingPoly
                    imageContext.productSearchParams = productSearchParams
                    annotateImageRequest.imageContext = imageContext

                    // Add the list of one thing to the request
                    add(annotateImageRequest)
                }
            }
            val annotateRequest = vision.images().annotate(batchAnnotateImagesRequest)
            // Due to a bug: requests to Vision API containing large images fail when GZipped.
            // Due to a bug: requests to Vision API containing large images fail when GZipped.
            annotateRequest.disableGZipContent = true
            Log.d(TAG, "created Cloud Vision request object, sending request")

            return annotateRequest.execute()
        }
    }
}
